rem # DJ - DosJavaStartup - 23-nov-2025 
echo off
cls

rem ############################### CUSTOM JAR
set jarName=RadioLocal-1.0.0.jar
set depsZipName=lib.zip
set depsFolderName=lib
set jreRepo=https://cdn.azul.com/zulu/bin/
set jre=zulu21.28.85-ca-fx-jre21.0.0-win_x64

rem ############################### CONFIG
set myPath=%~dp0
set distFolder=%myPath%dist
set tmpFolder=%myPath%temp

set jarPath=%distFolder%/%jarName%

rem ### JRE
set jreZip=%jre%.zip
set jreFolderName=jre
set jreFolder=%myPath%%jreFolderName%
set java=%jreFolder%\%jre%\bin\java.exe
set jreLink=%jreRepo%%jreZip%

rem ### Deps
rem set depsZipped=%distFolder%\%depsZipName%
rem set depsFolder=%distFolder%\%depsFolderName%
rem ############################### 

cd %myPath%

if not exist %tmpFolder%\ (
	mkdir %tmpFolder%
)

echo starting %jarName%

echo check local jvm...
if not exist %jreFolder%\ (
  echo JRE %jre% not found into %jreFolder%
  goto setup_jre
)
goto unzip_deps

:setup_jre
if not exist %tmpFolder%\%jreZip% (
	echo download jre from %jreLink% 	
	powershell -Command "Invoke-WebRequest %jreLink% -OutFile %tmpFolder%/%jreZip%"
)

if exist %tmpFolder%\%jreZip% (
	echo extracting jre
	powershell -Command "Expand-Archive -Force %tmpFolder%\%jreZip% %jreFolder%"
)

echo removing temporary files
del %tmpFolder%\%jreZip%

%java% -version

:unzip_deps
rem echo check dependencies...
rem if not exist %depsFolder% (
rem	echo extracting dependencies %depsZipped% 
rem	powershell -Command "Expand-Archive -Force %depsZipped% %tmpFolder%"
rem	move %tmpFolder%\%depsFolderName% %depsFolder%
rem )

:startup
if exist %tmpFolder% (
	echo removing temporary folder
	rmdir %tmpFolder%
)

%java% -jar %jarPath% %*

pause