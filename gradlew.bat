@rem
@rem Copyright 2015 the original author or authors.
@rem
@if "%DEBUG%" == "" @echo off
@setlocal

set DIR=%~dp0
if "%DIR%" == "" set DIR=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIR%

@rem Resolve the location of java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if "%ERRORLEVEL%" == "0" goto execute
goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%\bin\java.exe
if exist "%JAVA_EXE%" goto execute
goto fail

:execute
"%JAVA_EXE%" -Xmx64m -Xms64m -classpath "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
if "%ERRORLEVEL%" == "0" goto end
goto fail

:fail
echo.
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
echo.
exit /b 1

:end
@endlocal
