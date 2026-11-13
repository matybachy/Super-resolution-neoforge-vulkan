@echo off
rem Builds native\build\srvmr_ngx.dll with the Visual Studio C++ tools.
rem   build-windows.cmd <DLSS SDK folder> <Vulkan-Headers folder> [JDK folder]
setlocal
if "%~2"=="" (
  echo usage: build-windows.cmd ^<DLSS SDK folder^> ^<Vulkan-Headers folder^> [JDK folder]
  exit /b 2
)
set "SDK=%~1"
set "HEADERS=%~2"
if not "%~3"=="" set "JAVA_HOME=%~3"

set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
set "VS="
"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath > "%TEMP%\srvmr_vs.txt"
set /p VS=<"%TEMP%\srvmr_vs.txt"
del "%TEMP%\srvmr_vs.txt" >nul 2>&1
if not defined VS (
  echo Visual Studio with the C++ tools was not found.
  exit /b 1
)
call "%VS%\VC\Auxiliary\Build\vcvars64.bat" >nul || exit /b 1

set "HERE=%~dp0"
cmake -S "%HERE%." -B "%HERE%build" -G "NMake Makefiles" -DCMAKE_BUILD_TYPE=Release ^
  -DDLSS_SDK_DIR="%SDK%" -DVULKAN_HEADERS_DIR="%HEADERS%" %SRVMR_CMAKE_ARGS% || exit /b 1
cmake --build "%HERE%build" || exit /b 1
echo Built %HERE%build\srvmr_ngx.dll
