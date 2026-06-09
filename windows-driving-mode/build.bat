@echo off
chcp 65001 > nul
echo =============================================
echo  블루투스 운전 모드 — 단독 실행 파일 빌드
echo =============================================

pip show pyinstaller >nul 2>&1
if errorlevel 1 (
    echo PyInstaller 설치 중...
    pip install pyinstaller
)

pyinstaller ^
    --onefile ^
    --windowed ^
    --name "BluetoothDrivingMode" ^
    --add-data "assets;assets" ^
    main.py

if errorlevel 1 (
    echo [오류] 빌드 실패
    pause
    exit /b 1
)

echo.
echo =============================================
echo  빌드 완료: dist\BluetoothDrivingMode.exe
echo =============================================
pause
