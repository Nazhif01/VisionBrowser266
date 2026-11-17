package dev.nazhif.visionbrowser;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads whatever the operating system says is playing (Windows media session: Sonora, Spotify, any player) through a small PowerShell
 * helper, and sends play/pause/next/previous/seek back to it. Feeds the same state the browser player uses, so lyrics, island and
 * sync work identically. Windows only; nothing is downloaded and no audio is touched.
 */
final class SystemMedia {
    private static final Path DIR = Path.of("config");
    private static final Path SCRIPT = DIR.resolve("visionbrowser-media.ps1");
    private static final Path CMD = DIR.resolve("visionbrowser-media.cmd");

    private static final String PS = """
            param([string]$CmdFile,[string]$Filter)
            $ErrorActionPreference='SilentlyContinue'
            [Console]::OutputEncoding=[System.Text.Encoding]::UTF8
            Add-Type -AssemblyName System.Runtime.WindowsRuntime
            $asTask=([System.WindowsRuntimeSystemExtensions].GetMethods()|Where-Object{$_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'})[0]
            function Await($op,$type){$m=$asTask.MakeGenericMethod($type);$t=$m.Invoke($null,@($op));$t.Wait(-1)|Out-Null;$t.Result}
            [void][Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime]
            [void][Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties,Windows.Media.Control,ContentType=WindowsRuntime]
            $mgr=Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
            $baseRaw=-1;$baseAt=[DateTime]::UtcNow;$lastKey=''
            while($true){
              try{
                $s=$null
                if($Filter){foreach($x in $mgr.GetSessions()){if($x.SourceAppUserModelId -like "*$Filter*"){$s=$x;break}}}
                if(-not $s){$s=$mgr.GetCurrentSession()}
                if($s){
                  if(Test-Path $CmdFile){
                    $c=(Get-Content $CmdFile -Raw).Trim();Remove-Item $CmdFile -Force
                    if($c -eq 'toggle'){[void](Await ($s.TryTogglePlayPauseAsync()) ([bool]))}
                    elseif($c -eq 'next'){[void](Await ($s.TrySkipNextAsync()) ([bool]))}
                    elseif($c -eq 'prev'){[void](Await ($s.TrySkipPreviousAsync()) ([bool]))}
                    elseif($c -like 'seek:*'){$sec=[double]::Parse($c.Substring(5),[Globalization.CultureInfo]::InvariantCulture);[void](Await ($s.TryChangePlaybackPositionAsync([long]($sec*10000000))) ([bool]))}
                  }
                  $p=Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
                  $tl=$s.GetTimelineProperties();$pb=$s.GetPlaybackInfo()
                  $playing=($pb.PlaybackStatus.ToString() -eq 'Playing')
                  $raw=$tl.Position.TotalSeconds;$dur=($tl.EndTime-$tl.StartTime).TotalSeconds
                  $age=([DateTimeOffset]::UtcNow-$tl.LastUpdatedTime).TotalSeconds
                  if($playing -and $age -ge 0 -and $age -lt 7200){$pos=$raw+$age}
                  else{
                    if($raw -ne $baseRaw){$baseRaw=$raw;$baseAt=[DateTime]::UtcNow}
                    $pos=$raw;if($playing){$pos=$raw+([DateTime]::UtcNow-$baseAt).TotalSeconds}
                  }
                  $o=@{title=[string]$p.Title;artist=[string]$p.Artist;album=[string]$p.AlbumTitle;pos=[math]::Round($pos,3);dur=[math]::Round($dur,3);playing=$playing;app=[string]$s.SourceAppUserModelId}
                  [Console]::Out.WriteLine(($o|ConvertTo-Json -Compress))
                } else {
                  if(Test-Path $CmdFile){Remove-Item $CmdFile -Force}
                  [Console]::Out.WriteLine('{"none":true}')
                }
                [Console]::Out.Flush()
              }catch{}
              Start-Sleep -Milliseconds 250
            }
            """;

    private static Process proc;
    private static Thread reader;
    private static volatile String status = "";

    private SystemMedia() { }

    static String status() { return status; }

    /** starts or stops the helper so it matches the setting */
    static synchronized void ensureState() {
        boolean want = VisionSettings.systemMedia;
        boolean running = proc != null && proc.isAlive();
        if (want && !running) start();
        else if (!want && proc != null) stop();
    }

    private static void start() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            status = "System media works on Windows only";
            VisionMediaBridge.setStatus(status);
            return;
        }
        try {
            Files.createDirectories(DIR);
            Files.writeString(SCRIPT, PS, StandardCharsets.UTF_8);
            Files.deleteIfExists(CMD);
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                    "-File", SCRIPT.toAbsolutePath().toString(), "-CmdFile", CMD.toAbsolutePath().toString(), "-Filter", VisionSettings.systemMediaApp);
            pb.redirectErrorStream(false);
            Process p = pb.start();
            proc = p;
            status = "Waiting for a playing app (Sonora, Spotify...)";
            VisionMediaBridge.setStatus(status);
            Thread t = new Thread(() -> readLoop(p), "VisionBrowser-SystemMedia");
            t.setDaemon(true);
            t.start();
            reader = t;
            Runtime.getRuntime().addShutdownHook(new Thread(p::destroyForcibly));
        } catch (Throwable error) {
            status = "Cannot start system media: " + error.getClass().getSimpleName();
            VisionMediaBridge.setStatus(status);
        }
    }

    private static void stop() {
        Process p = proc;
        proc = null;
        if (p != null) p.destroyForcibly();
        status = "";
    }

    private static void readLoop(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (p != proc) break;
                line = line.trim();
                if (!line.startsWith("{")) continue;
                try {
                    JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                    if (o.has("none")) {
                        VisionMediaBridge.applySystem("", "", "", 0, 0, false);
                        continue;
                    }
                    VisionMediaBridge.applySystem(s(o, "title"), s(o, "artist"), s(o, "album"), d(o, "pos"), d(o, "dur"),
                            o.has("playing") && o.get("playing").getAsBoolean());
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
    }

    private static String s(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }
    private static double d(JsonObject o, String k) { try { return o.has(k) ? o.get(k).getAsDouble() : 0; } catch (Throwable e) { return 0; } }

    /** toggle, next, prev or seek:SECONDS */
    static void command(String c) {
        try {
            Files.writeString(CMD, c, StandardCharsets.UTF_8);
        } catch (Throwable ignored) { }
    }
}
