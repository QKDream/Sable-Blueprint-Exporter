$ErrorActionPreference = "Stop"
# Builds blueprint_exporter against whatever modpack is currently installed, the
# same way firecontrolcompat/build.ps1 does: plain javac with the pack's mods on
# the classpath. No Gradle and no NeoGradle - this mod only needs to compile
# against classes that already ship in the pack.
$ws = "C:/Users/1/Documents/DSH/fire-control-eye/sable_blueprint_kit/exporter"
$mcLib = "D:/.minecraft/libraries"

function Get-VersionDirs([string]$filter) {
    Get-ChildItem "D:/.minecraft/versions" -Directory |
        Where-Object { @(Get-ChildItem (Join-Path $_.FullName "mods") -Filter $filter -File -ErrorAction SilentlyContinue).Count -gt 0 }
}
$versionRoot = @(Get-VersionDirs "sable-neoforge-*.jar*") |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $versionRoot) {
    $versionRoot = @(Get-VersionDirs "create-1.21.1-*.jar*") | Select-Object -First 1
}
if (-not $versionRoot) { Write-Host "No modpack with sable/create found under D:/.minecraft/versions"; exit 1 }
$modsDir = Join-Path $versionRoot.FullName "mods"
Write-Host "Using mods dir: $modsDir"

$javac = "D:/.minecraft/runtime/java-runtime-delta/bin/javac.exe"
$jarExe = "D:/.minecraft/runtime/java-runtime-delta/bin/jar.exe"

$cpDir = "$ws/build/modscp"
New-Item -ItemType Directory -Force $cpDir | Out-Null

function Resolve-ActiveJar([string]$family) {
    $all = @(Get-ChildItem -LiteralPath $modsDir -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "$family*" -and ($_.Name -like "*.jar" -or $_.Name -like "*.jar.disabled") })
    if ($all.Count -eq 0) { return $null }
    $enabled = @($all | Where-Object { $_.Name -like "*.jar" })
    if ($enabled.Count -gt 0) { $all = $enabled }
    return ($all | Sort-Object Name -Descending | Select-Object -First 1)
}

$need = @{}
# Match on a substring rather than a prefix: the pack renames jars with Chinese
# tags, so "create-1.21.1-*" never matches "[机械动力] create-1.21.1-6.0.10.jar".
function Resolve-Contains([string]$needle) {
    $all = @(Get-ChildItem -LiteralPath $modsDir -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "*$needle*" -and ($_.Name -like "*.jar" -or $_.Name -like "*.jar.disabled") })
    if ($all.Count -eq 0) { return $null }
    $enabled = @($all | Where-Object { $_.Name -like "*.jar" })
    if ($enabled.Count -gt 0) { $all = $enabled }
    return ($all | Sort-Object Name -Descending | Select-Object -First 1)
}
foreach ($needle in @("sable-neoforge-", "create-1.21.1-", "sable-photomancy-", "ldlib2-")) {
    $jar = Resolve-Contains $needle
    if (-not $jar) { Write-Host "MISSING required jar: *$needle* in $modsDir"; exit 1 }
    $need[$jar.Name] = $jar.FullName
    Write-Host "Using $($jar.Name)"
}
foreach ($name in $need.Keys) {
    $src = $need[$name]
    # javac reads the classpath from an @argfile, and a '[' in a jar name there is
    # parsed as an option flag - so stage every jar under a plain ASCII name.
    $simple = switch -Wildcard ($name) {
        "*sable-neoforge-*"     { "sable.jar" }
        "*create-1.21.1-*"      { "create.jar" }
        "*sable-photomancy-*"   { "photomancy.jar" }
        "*ldlib2-*"             { "ldlib2.jar" }
        default                 { ($name -replace '[^\w\.\-]', '_') }
    }
    $dst = Join-Path $cpDir $simple
    if (-not (Test-Path -LiteralPath $dst) -or
        (Get-Item -LiteralPath $src).Length -ne (Get-Item -LiteralPath $dst).Length) {
        Copy-Item -LiteralPath $src -Destination $dst -Force
    }
    Write-Host "Staged $name -> $simple"
}

# catnip (and the SuperByteBuffer / SchematicLevel classes) ride along inside the
# create jar as a jar-in-jar. Pull it out so SchematicLevel is on the classpath.
Add-Type -AssemblyName System.IO.Compression.FileSystem
# Jar-in-jar libraries the staged mods depend on. `ponder` carries catnip
# (SchematicLevel and the render classes), and ldlib2 carries taffy, which the
# blueprint tool's UI layout types come from. Neither is a separate file in mods\,
# so both have to be pulled out of their host jar.
$jij = @(
    @{ Host = "create.jar"; Match = "META-INF/jarjar/ponder-*.jar"; Why = "SchematicLevel and the catnip render classes" },
    @{ Host = "ldlib2.jar"; Match = "META-INF/jarjar/taffy-*.jar";  Why = "the blueprint tool's UI layout types" },
    # taffy's own method signatures reference the yoga enums, so javac cannot read
    # TaffyPosition.layout() without this on the classpath even though the code
    # never mentions yoga directly.
    @{ Host = "ldlib2.jar"; Match = "META-INF/jarjar/yoga-*.jar";   Why = "the enum types taffy's signatures refer to" }
)
foreach ($item in $jij) {
    $hostJar = Join-Path $cpDir $item.Host
    if (-not (Test-Path -LiteralPath $hostJar)) { Write-Host "not staged: $($item.Host)"; exit 1 }
    $zip = [System.IO.Compression.ZipFile]::OpenRead($hostJar)
    try {
        $entry = $zip.Entries | Where-Object { $_.FullName -like $item.Match } | Select-Object -First 1
        if (-not $entry) { Write-Host "no $($item.Match) inside $($item.Host)"; exit 1 }
        $leaf = Split-Path $entry.FullName -Leaf
        $target = Join-Path $cpDir $leaf
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
        Write-Host "Using $leaf for $($item.Why)"
    } finally { $zip.Dispose() }
}

$cpParts = New-Object System.Collections.Generic.List[string]
foreach ($rel in @(
        "net/neoforged/neoforge/21.1.228/neoforge-21.1.228-client.jar",
        "net/neoforged/neoforge/21.1.228/neoforge-21.1.228-universal.jar",
        "net/minecraft/client/1.21.1-20240808.144430/client-1.21.1-20240808.144430-srg.jar",
        "net/minecraft/client/1.21.1-20240808.144430/client-1.21.1-20240808.144430-extra.jar",
        "net/neoforged/fancymodloader/loader/4.0.42/loader-4.0.42.jar",
        "net/neoforged/bus/8.0.5/bus-8.0.5.jar",
        "net/neoforged/coremods/7.0.3/coremods-7.0.3.jar",
        "net/neoforged/mergetool/2.0.3/mergetool-2.0.3-api.jar",
        "org/spongepowered/mixin/0.8.7/mixin-0.8.7.jar",
        "org/ow2/asm/asm/9.10.1/asm-9.10.1.jar",
        "org/ow2/asm/asm-tree/9.10.1/asm-tree-9.10.1.jar",
        "org/ow2/asm/asm-analysis/9.10.1/asm-analysis-9.10.1.jar",
        "org/ow2/asm/asm-commons/9.10.1/asm-commons-9.10.1.jar",
        "com/mojang/datafixerupper/6.0.6/datafixerupper-6.0.6.jar",
        "com/mojang/brigadier/1.3.10/brigadier-1.3.10.jar",
        "org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar",
        "io/netty/netty-buffer/4.1.97.Final/netty-buffer-4.1.97.Final.jar",
        "io/netty/netty-common/4.1.97.Final/netty-common-4.1.97.Final.jar",
        "com/google/guava/guava/33.5.0-jre/guava-33.5.0-jre.jar",
        "com/google/code/gson/gson/2.10.1/gson-2.10.1.jar",
        "org/joml/joml/1.10.5/joml-1.10.5.jar",
        # KeyMapping needs GLFW for the default key constant.
        "org/lwjgl/lwjgl-glfw/3.3.3/lwjgl-glfw-3.3.3.jar",
        "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar",
        # LowDragLib2 / Photomancy classes are annotated with @Nullable, and javac
        # needs the annotation on the classpath to read their signatures at all.
        "com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.jar")) {
    $cpParts.Add("$mcLib/$rel")
}
Get-ChildItem -LiteralPath $cpDir -Filter "*.jar" | ForEach-Object { $cpParts.Add($_.FullName.Replace('\','/')) }

$missing = $cpParts | Where-Object { -not (Test-Path -LiteralPath $_) }
if ($missing) { Write-Host "MISSING:"; $missing | ForEach-Object { Write-Host "  $_" }; exit 1 }
$classpath = $cpParts -join ";"
Write-Host "Classpath entries: $($cpParts.Count)"

$outDir = "$ws/build/classes"
if (Test-Path $outDir) { Remove-Item -Recurse -Force $outDir -ErrorAction SilentlyContinue }
New-Item -ItemType Directory -Force $outDir | Out-Null

$sources = Get-ChildItem -Path "$ws/src/main/java" -Recurse -Filter "*.java"
Write-Host "Compiling $($sources.Count) java files..."
$argFile = "$ws/build/javac_argfile.txt"
$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("-d"); $lines.Add($outDir)
$lines.Add("-cp"); $lines.Add($classpath)
$lines.Add("-encoding"); $lines.Add("UTF-8")
$lines.Add("-proc:none")
$lines.Add("-nowarn")
$sources | ForEach-Object { $lines.Add($_.FullName.Replace('\','/')) }
[System.IO.File]::WriteAllLines($argFile, $lines, (New-Object System.Text.UTF8Encoding($false)))

& $javac "@$argFile"
if ($LASTEXITCODE -ne 0) { Write-Host "COMPILATION FAILED (exit $LASTEXITCODE)"; exit 1 }
$classCount = (Get-ChildItem -Path $outDir -Recurse -Filter *.class | Measure-Object).Count
Write-Host "Compilation OK! ($classCount classes)"

$stage = "$ws/build/jar_tmp"
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Force $stage | Out-Null
Copy-Item -Recurse -Force "$outDir/*" $stage
Copy-Item -Recurse -Force "$ws/src/main/resources/*" $stage

$jarOut = "$ws/blueprintexporter-1.0.0.jar"
if (Test-Path $jarOut) { Remove-Item -Force $jarOut }
Push-Location $stage
& $jarExe cf $jarOut *
$jarExit = $LASTEXITCODE
Pop-Location
if (Test-Path $jarOut) {
    Write-Host "BUILD SUCCESS: $jarOut"
} else {
    Write-Host "JAR FAILED (exit $jarExit)"
    exit 1
}
