@echo off
REM ============================================================================
REM Run the backend LOCALLY on the DEMO profile against the local `shifa_demo`
REM database. This NEVER touches `shifa_dashboard` (that is the `local`/prod DB
REM used to test current production locally).
REM
REM What the demo profile does (see application-demo.yml):
REM   * DB            -> shifa_demo (empty on first boot; Flyway applies V1..latest + seeds)
REM   * QuikShipX     -> OFF (every order runs the in-house delivery lifecycle)
REM   * Storage       -> DB (no S3; self-contained)
REM   * Mail/WhatsApp/Courier -> MOCK (nothing leaves the machine)
REM
REM Brand: set BRAND_NAME / BRAND_ORDER_CODE_PREFIX / MAIL_BRAND_NAME below for
REM the white-label client this demo represents.
REM
REM Usage:  from the repo root, double-click or run:  backend\run-demo-local.cmd
REM ============================================================================

REM --- Spring profile -----------------------------------------------------------
set SPRING_PROFILES_ACTIVE=demo

REM --- Local database (root/root@123, matching application-local.yml dev creds) --
set DB_HOST=localhost
set DB_PORT=3306
set DB_NAME=shifa_demo
set DB_USERNAME=root
set DB_PASSWORD=root@123

REM --- Storage: in the DB so no S3 is needed locally ----------------------------
set STORAGE_PROVIDER=DB

REM --- White-label brand (shown on order codes, invoices, labels, emails) -------
set BRAND_NAME=Weblithic
set BRAND_ORDER_CODE_PREFIX=DEMO-
set MAIL_BRAND_NAME=Weblithic

REM --- Local mysqldump (not on PATH; needed only if a backup is triggered) -------
set MYSQLDUMP_PATH=C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe

REM --- Demo-only JWT secret (>= 32 chars; NOT the prod secret) -------------------
set JWT_SECRET=demo-local-only-jwt-secret-change-me-please-32char

echo Starting backend on the DEMO profile against %DB_NAME% ...
call "C:\Program Files\apache-maven-3.9.12\bin\mvn.cmd" -f "%~dp0pom.xml" -DskipTests spring-boot:run
