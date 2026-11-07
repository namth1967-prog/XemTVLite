@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"
title Upload len GitHub

rem ---- doc so phien ban tu app\build.gradle.kts ----
set "VER=?"
for /f "tokens=2 delims==" %%a in ('findstr /c:"versionName" app\build.gradle.kts') do set "VER=%%a"
set VER=%VER: =%
set VER=%VER:"=%

echo ==================================================
echo   UPLOAD XEM TV LITE  -  PHIEN BAN %VER%
echo ==================================================
echo.

where git >nul 2>&1
if errorlevel 1 (
  echo [LOI] Chua cai git. Tai tai git-scm.com roi chay lai.
  goto :fail
)
if not exist ".git" (
  echo [LOI] Thu muc nay chua phai kho git. Hay chay file nay trong thu muc XemTVLite da clone tu GitHub.
  goto :fail
)

rem ---- 1. Dam bao .gitignore chan cac file nang/khong can ----
call :ensure ".tools/"
call :ensure "build.log"
call :ensure ".kotlin/"
call :ensure ".gradle/"
call :ensure "build/"
call :ensure "local.properties"
call :ensure "*.apk"
call :ensure "*.zip"

rem ---- 2. Go cac file do ra khoi git (neu lo bi theo doi), file that tren may van con ----
git rm -r --cached --ignore-unmatch -q .tools build.log .kotlin local.properties *.apk *.zip >nul 2>&1

rem ---- 3. Commit ----
git add -A
git diff --cached --quiet
if not errorlevel 1 (
  echo [..] Khong co thay doi moi de commit. Thu day cac commit con lai len GitHub...
  goto :push
)
git commit -q -m "Phien ban %VER%"
if errorlevel 1 goto :fail
echo [OK] Da commit: Phien ban %VER%

rem ---- 4. Push ----
:push
echo [..] Dang day len GitHub...
git push
if errorlevel 1 (
  echo.
  echo [LOI] Push that bai. Chup man hinh doan loi o tren gui cho Claude.
  goto :fail
)

echo.
echo ==================================================
echo   XONG. Phien ban %VER% da len GitHub.
echo   Mo trang Actions, doi dau tich xanh roi tai
echo   APK ten XemTVLite-v%VER% o muc Artifacts.
echo ==================================================
set "URL="
for /f "delims=" %%u in ('git remote get-url origin') do set "URL=%%u"
if defined URL (
  set URL=%URL:.git=%
  start "" "%URL%/"
)
pause
exit /b 0

:ensure
findstr /x /c:"%~1" ".gitignore" >nul 2>&1
if errorlevel 1 echo %~1>> ".gitignore"
exit /b 0

:fail
echo.
pause
exit /b 1
