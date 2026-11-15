@echo off
setlocal EnableDelayedExpansion
echo Building IPTree...

:: Create bin directory if it doesn't exist
if not exist bin mkdir bin
if not exist versionhistory mkdir versionhistory

:: Determine latest build and its hash
set "max_build=0"
set "latest_jar="

:: Check versionhistory for highest build number
for %%f in (versionhistory\IPTree_b*.jar) do (
    set "filename=%%~nf"
    set "num=!filename:IPTree_b=!"
    if !num! gtr !max_build! (
        set "max_build=!num!"
        set "latest_jar=%%f"
    )
)

:: Check current directory for existing build
for %%f in (IPTree_b*.jar) do (
    set "filename=%%~nf"
    set "num=!filename:IPTree_b=!"
    if !num! gtr !max_build! (
        set "max_build=!num!"
        set "latest_jar=%%f"
    )
)

set "old_hash="
if defined latest_jar (
    echo Checking hash of !latest_jar!...
    for /f "skip=1 delims=" %%H in ('certutil -hashfile "!latest_jar!" MD5') do (
        if not defined old_hash set "old_hash=%%H"
    )
    set "old_hash=!old_hash: =!"
)

:: Attempt to locate Java 8 JDK
set "JDK_BIN="
if exist "C:\Program Files\Eclipse Adoptium\jdk-8.0.472.8-hotspot\bin\javac.exe" (
    set "JDK_BIN=C:\Program Files\Eclipse Adoptium\jdk-8.0.472.8-hotspot\bin"
)

if not defined JDK_BIN (
    for /f "tokens=2*" %%A in ('reg query "HKLM\SOFTWARE\JavaSoft\Java Development Kit\1.8" /v JavaHome 2^>nul') do (
        if exist "%%B\bin\javac.exe" set "JDK_BIN=%%B\bin"
    )
)

if not defined JDK_BIN (
    for %%P in ("%ProgramFiles%" "%ProgramFiles(x86)%") do (
        for /d %%D in (
            "%%~P\Eclipse Adoptium\jdk-8*"
            "%%~P\AdoptOpenJDK\jdk-8*"
            "%%~P\Java\jdk1.8*"
            "%%~P\Java\jdk-8*"
            "%%~P\Amazon Corretto\jdk1.8*"
            "%%~P\Zulu\zulu-8*"
            "%%~P\BellSoft\LibericaJDK-8*"
        ) do (
            if not defined JDK_BIN if exist "%%~D\bin\javac.exe" set "JDK_BIN=%%~D\bin"
        )
    )
)

if defined JDK_BIN (
    set "JAVAC=!JDK_BIN!\javac.exe"
    set "JAR=!JDK_BIN!\jar.exe"
    echo Using JDK at: !JDK_BIN!
) else (
    set "JAVAC=javac"
    set "JAR=jar"
    echo No dedicated JDK 8 directory found; falling back to PATH javac/jar.
)

:: Compile
echo Compiling...
"!JAVAC!" -d bin -cp spigot-1.8.8.jar -source 1.8 -target 1.8 src/ac/tree/testserver/iptree/*.java

:: Copy plugin.yml
echo Copying plugin.yml...
copy src\plugin.yml bin\plugin.yml >nul
copy src\config.yml bin\config.yml >nul

:: Package to temp file
echo Packaging...
"!JAR!" cvf IPTree_temp.jar -C bin . >nul

:: Calculate new hash
set "new_hash="
for /f "skip=1 delims=" %%H in ('certutil -hashfile "IPTree_temp.jar" MD5') do (
    if not defined new_hash set "new_hash=%%H"
)
set "new_hash=!new_hash: =!"

:: Compare
if "!old_hash!"=="!new_hash!" (
    echo Build identical to previous version b!max_build!. Discarding...
    del IPTree_temp.jar
) else (
    echo Changes detected or new build.
    
    :: Move old jars from root to versionhistory
    for %%f in (IPTree_b*.jar) do (
        echo Moving %%f to versionhistory...
        move "%%f" "versionhistory\" >nul
    )
    
    set /a next_build=max_build+1
    echo Updating src\plugin.yml version to b!next_build!...
    powershell -NoProfile -Command "$c = [System.IO.File]::ReadAllText('src\plugin.yml') -replace 'version:[ \t]*\S+', 'version: b!next_build!'; [System.IO.File]::WriteAllText('src\plugin.yml', $c)"
    copy src\plugin.yml bin\plugin.yml >nul

    echo Creating IPTree_b!next_build!.jar...
    del IPTree_temp.jar
    "!JAR!" cvf IPTree_b!next_build!.jar -C bin . >nul
    echo Build complete: b!next_build!
)

:: Wait before closing
ping 1.1.1.1 -n 2 -w 800 >nul
ping 1.1.1.1 -n 2 -w 800 >nul