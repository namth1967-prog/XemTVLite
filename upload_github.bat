@echo off
chcp 65001 > nul
cls
echo ==================================================
echo   CONG CU TU DONG UPLOAD FOLDER LEN GITHUB
echo ==================================================
echo.

:: Kiem tra xem Git da duoc cai dat chua
where git >nul 2>nul
if %errorlevel% neq 0 (
    echo [LOI] May tinh cua ban chua cai dat Git!
    echo Vui long tai va cai dat Git tai: https://git-scm.com/
    goto END
)

:: Kiem tra xem da khoi tao Git repository chua
if not exist ".git" (
    echo [KHOI TAO] Thu muc nay chua tung ket noi GitHub.
    echo.
    set /p REPO_URL="Dán URL Repository cua ban vao day (Click chuot phai de Dan): "
    
    if "%REPO_URL%"=="" (
        echo [LOI] URL khong duoc de trong!
        goto END
    )
    
    echo.
    echo 1. Dang khoi tao Git...
    git init
    
    echo 2. Dang ket noi den Repository...
    git remote add origin %REPO_URL%
    
    echo 3. Dang chuyen nhanh chinh thanh 'main'...
    git branch -M main
) else (
    echo [THONG BAO] Da tim thay ket noi Git co san.
)

echo.
set /p COMMIT_MSG="Nhap ghi chu cho lan upload nay (Nhan Enter de de mac dinh): "
if "%COMMIT_MSG%"=="" (
    set COMMIT_MSG="Auto upload folder via script"
)

echo.
echo 4. Dang chuan bi cac file...
git add .

echo 5. Dang tao diem luu (Commit)...
git commit -m "%COMMIT_MSG%"

echo 6. Dang tai len (Push) len GitHub...
echo (Neu co cua so hien len, vui long chon 'Sign in with your browser')
git push -u origin main

if %errorlevel% equ 0 (
    echo.
    echo ==================================================
    echo   THANH CONG! Folder da duoc upload len GitHub.
    echo ==================================================
) else (
    echo.
    echo [LOI] Co loi xay ra khi push code. Vui long kiem tra lai quyen truy cap hoac internet.
)

:END
echo.
pause
