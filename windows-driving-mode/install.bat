@echo off
chcp 65001 > nul
echo =============================================
echo  블루투스 운전 모드 — 의존성 설치
echo =============================================

where python >nul 2>&1
if errorlevel 1 (
    echo [오류] Python 3.9 이상이 필요합니다.
    echo https://www.python.org/downloads/ 에서 설치해 주세요.
    pause
    exit /b 1
)

echo Python 버전 확인:
python --version

echo.
echo pip 패키지 설치 중...
pip install -r requirements.txt

if errorlevel 1 (
    echo.
    echo [오류] 패키지 설치에 실패했습니다.
    pause
    exit /b 1
)

echo.
echo =============================================
echo  설치 완료! run.bat 으로 실행하세요.
echo =============================================
pause
