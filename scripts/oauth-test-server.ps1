<#
.SYNOPSIS
    Stands up a throwaway PostgreSQL server that logs a role in with OAuth, for the OAuth tests.

.DESCRIPTION
    A server checks an OAuth bearer token through a validator module, and PostgreSQL ships none. This builds the
    one in scripts/oauth-test-validator.c, which takes a token to name the role it logs in as, against the server
    headers of the install it uses, and puts a server on a port of its own with TLS on and the role `legatus` under
    an `oauth` line of pg_hba.conf. Everything lives inside `.oauth-test/` at the repository root, so nothing about
    an existing install is touched and `remove` really does remove all of it.

    Building the validator needs Visual Studio with the C++ build tools, found through vswhere. The install needs
    its server headers and postgres.lib, which the EDB installer puts in by default.

    It is meant to be run for as long as the tests take and then thrown away - `remove` when you are done, so there
    is no server left sitting in the background.

    On Linux the same server is easier to get from Docker; the recipe CI uses is in `.github/workflows/tests.yml`,
    job `test-oauth`.

.PARAMETER Action
    start   Create whatever is missing, then start the server and report how to run the tests.
    stop    Stop the server, keeping the data directory, certificate and validator for next time.
    remove  Stop the server and delete `.oauth-test/` outright.
    status  Report whether the instance exists and whether it is listening.

.PARAMETER Bin
    The bin directory of the PostgreSQL 18 or newer to use. Defaults to initdb on PATH, then the newest install
    under C:\Program Files\PostgreSQL.

.EXAMPLE
    .\scripts\oauth-test-server.ps1 start

.EXAMPLE
    .\scripts\oauth-test-server.ps1 remove
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('start', 'stop', 'remove', 'status')]
    [string]$Action = 'start',

    [string]$Bin
)

$ErrorActionPreference = 'Stop'

# OAuthTest connects to this port and logs in as postgres with this password for anything but OAuth, so none of
# them can change here alone.
$Port     = 5438
$Password = '1234'

$root      = Split-Path -Parent $PSScriptRoot
$base      = Join-Path $root '.oauth-test'
$data      = Join-Path $base 'data'
$libDir    = Join-Path $base 'lib'
$certDir   = Join-Path $base 'certs'
$log       = Join-Path $base 'server.log'
$validator = 'octavius_test_validator'

function Find-PostgresBin {
    if ($Bin) {
        if (-not (Test-Path (Join-Path $Bin 'initdb.exe'))) { throw "No initdb.exe in $Bin." }
        return $Bin
    }

    $onPath = Get-Command initdb -ErrorAction SilentlyContinue
    if ($onPath) { return Split-Path $onPath.Source }

    $installs = Get-ChildItem 'C:\Program Files\PostgreSQL' -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^\d+$' } |
        Sort-Object { [int]$_.Name } -Descending

    foreach ($install in $installs) {
        $candidate = Join-Path $install.FullName 'bin'
        if (Test-Path (Join-Path $candidate 'initdb.exe')) { return $candidate }
    }

    throw "No PostgreSQL installation found. Put initdb on PATH, pass -Bin, or install PostgreSQL 18 or newer."
}

function Find-OpenSsl {
    # Git's own copy first, as in ssl-test-server.ps1: a Strawberry Perl install puts a broken openssl.exe ahead of
    # it on PATH.
    $candidates = @(
        'C:\Program Files\Git\usr\bin\openssl.exe',
        'C:\Program Files\Git\mingw64\bin\openssl.exe',
        'C:\Program Files\OpenSSL-Win64\bin\openssl.exe'
    )
    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) { return $candidate }
    }

    $onPath = Get-Command openssl -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }

    throw "openssl not found. It ships with Git for Windows; put it on PATH or install OpenSSL."
}

function Find-VcVars {
    $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
    if (-not (Test-Path $vswhere)) {
        throw "vswhere.exe not found. Building the validator needs Visual Studio with the C++ build tools."
    }

    $install = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 `
        -property installationPath
    $vcvars = if ($install) { Join-Path $install 'VC\Auxiliary\Build\vcvars64.bat' }
    if (-not $vcvars -or -not (Test-Path $vcvars)) {
        throw "No Visual Studio with the C++ build tools found. Building the validator needs them."
    }
    return $vcvars
}

function Test-Listening {
    param([int]$OnPort)

    try {
        $client = [System.Net.Sockets.TcpClient]::new()
        $connect = $client.BeginConnect('localhost', $OnPort, $null, $null)
        $ok = $connect.AsyncWaitHandle.WaitOne(500)
        if ($ok) { $client.EndConnect($connect) }
        $client.Close()
        return $ok
    } catch {
        return $false
    }
}

function New-Validator {
    param([string]$PgBin)

    $install = Split-Path -Parent $PgBin
    $server = Join-Path $install 'include\server'
    $postgresLib = Join-Path $install 'lib\postgres.lib'
    if (-not (Test-Path (Join-Path $server 'libpq\oauth.h'))) {
        throw "No server headers with OAuth in $server. The install has to be PostgreSQL 18 or newer, with headers."
    }
    if (-not (Test-Path $postgresLib)) {
        throw "No $postgresLib to link the validator against."
    }

    $vcvars = Find-VcVars
    $source = Join-Path $PSScriptRoot 'oauth-test-validator.c'
    $build = Join-Path $base 'build'
    New-Item -ItemType Directory -Force -Path $build, $libDir | Out-Null

    $includes = @(
        (Join-Path $server 'port\win32_msvc'),
        (Join-Path $server 'port\win32'),
        $server,
        (Join-Path $install 'include')
    ) | ForEach-Object { "/I`"$_`"" }

    Write-Host "Building the validator with $vcvars"
    $command = "call `"$vcvars`" >nul && cd /d `"$build`" && cl /nologo /LD /MD /O2 $($includes -join ' ') " +
        "`"$source`" /link `"$postgresLib`" /OUT:`"$(Join-Path $libDir "$validator.dll")`""
    $output = cmd /c $command 2>&1
    if ($LASTEXITCODE -ne 0) {
        $output | Write-Host
        throw "Building the validator failed with exit code $LASTEXITCODE."
    }
}

function New-Certificate {
    $openssl = Find-OpenSsl
    New-Item -ItemType Directory -Force -Path $certDir | Out-Null

    # Self-signed for CN=localhost: the tests connect under sslmode=require, which encrypts without verifying.
    & $openssl req -new -x509 -days 3650 -nodes `
        -out (Join-Path $certDir 'server.crt') -keyout (Join-Path $certDir 'server.key') `
        -subj '/CN=localhost' 2>$null

    if (-not (Test-Path (Join-Path $certDir 'server.crt'))) {
        throw "Certificate generation failed - no server.crt was produced."
    }
}

function New-Instance {
    param([string]$PgBin)

    if (Test-Listening $Port) {
        throw "Something already listens on port $Port, which the OAuth tests connect to."
    }

    $version = (& (Join-Path $PgBin 'initdb.exe') --version) -replace '^.*?(\d+).*$', '$1'
    if ([int]$version -lt 18) {
        throw "Found PostgreSQL $version. OAuth needs PostgreSQL 18 or newer."
    }

    New-Validator $PgBin
    New-Certificate

    $pwFile = Join-Path $base 'pwfile.txt'
    Set-Content -Path $pwFile -Value $Password -NoNewline

    Write-Host "Creating data directory at $data"
    & (Join-Path $PgBin 'initdb.exe') -D $data -U postgres --auth=scram-sha-256 --pwfile=$pwFile -E UTF8 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "initdb failed with exit code $LASTEXITCODE." }

    Remove-Item $pwFile -Force

    # PostgreSQL wants the key beside its own files; on Windows it skips the permission check that would otherwise
    # reject a world-readable one.
    Copy-Item (Join-Path $certDir 'server.crt'), (Join-Path $certDir 'server.key') -Destination $data

    # The validator stays in .oauth-test/lib, found through dynamic_library_path, so the install is not written to.
    $libPath = $libDir -replace '\\', '/'
    Add-Content -Path (Join-Path $data 'postgresql.conf') -Value @"

# --- added by scripts/oauth-test-server.ps1 ---
port = $Port
listen_addresses = 'localhost'
ssl = on
ssl_cert_file = 'server.crt'
ssl_key_file = 'server.key'
dynamic_library_path = '$libPath;`$libdir'
oauth_validator_libraries = '$validator'
"@

    # legatus logs in with OAuth, over TLS and, so the tests can check that the driver refuses to send a token
    # there, without it. pg_hba is first-match, so these go above the defaults.
    $hba = Join-Path $data 'pg_hba.conf'
    $existing = Get-Content $hba -Raw
    Set-Content -Path $hba -Value @"
# --- added by scripts/oauth-test-server.ps1 ---
hostssl all legatus 127.0.0.1/32 oauth issuer="https://issuer.octavius.test" scope="openid"
hostssl all legatus ::1/128      oauth issuer="https://issuer.octavius.test" scope="openid"
host    all legatus 127.0.0.1/32 oauth issuer="https://issuer.octavius.test" scope="openid"
host    all legatus ::1/128      oauth issuer="https://issuer.octavius.test" scope="openid"

$existing
"@
}

function Start-Instance {
    param([string]$PgBin)

    if (Test-Listening $Port) {
        Write-Host "Already listening on port $Port."
        return
    }

    Write-Host "Starting server on port $Port"
    Start-Process -FilePath (Join-Path $PgBin 'pg_ctl.exe') `
        -ArgumentList @('-D', $data, '-l', $log, 'start') `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $base 'pg_ctl.out') `
        -RedirectStandardError (Join-Path $base 'pg_ctl.err')

    $ready = $false
    foreach ($attempt in 1..30) {
        Start-Sleep -Seconds 1
        & (Join-Path $PgBin 'pg_isready.exe') -h localhost -p $Port -q 2>$null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    }

    if (-not $ready) {
        throw "Server did not become ready. See $log."
    }

    $env:PGPASSWORD = $Password
    $psql = Join-Path $PgBin 'psql.exe'
    $exists = & $psql -h localhost -p $Port -U postgres -d postgres -tAc `
        "SELECT 1 FROM pg_database WHERE datname = 'octavius_test'"
    if (-not $exists) {
        & (Join-Path $PgBin 'createdb.exe') -h localhost -p $Port -U postgres octavius_test
        if ($LASTEXITCODE -ne 0) { throw "createdb failed with exit code $LASTEXITCODE." }
        Write-Host "Created database octavius_test."
    }

    $roleExists = & $psql -h localhost -p $Port -U postgres -d postgres -tAc `
        "SELECT 1 FROM pg_roles WHERE rolname = 'legatus'"
    if (-not $roleExists) {
        & $psql -h localhost -p $Port -U postgres -d postgres -c "CREATE ROLE legatus LOGIN" | Out-Null
        Write-Host "Created role legatus (OAuth authentication)."
    }
}

function Stop-Instance {
    param([string]$PgBin)

    if (-not (Test-Path (Join-Path $data 'postmaster.pid'))) {
        Write-Host "Port ${Port}: not running."
        return
    }

    & (Join-Path $PgBin 'pg_ctl.exe') -D $data -m fast stop | Out-Null
    Write-Host "Port ${Port}: stopped."
}

function Write-TestInstructions {
    Write-Host ""
    Write-Host "Ready. To run the OAuth tests against it:" -ForegroundColor Green
    Write-Host ""
    Write-Host "  `$env:TEST_OAUTH = `"true`""
    Write-Host "  .\gradlew.bat :driver:test --tests `"*OAuthTest*`""
    Write-Host ""
    Write-Host "And when you are done, so it is not left running:"
    Write-Host "  Remove-Item Env:TEST_OAUTH"
    Write-Host "  .\scripts\oauth-test-server.ps1 remove"
    Write-Host ""
}

$pgBin = Find-PostgresBin

switch ($Action) {
    'start' {
        if (-not (Test-Path $data)) { New-Instance $pgBin }
        Start-Instance $pgBin
        Write-TestInstructions
    }

    'stop' {
        if (-not (Test-Path $data)) { Write-Host "Nothing to stop - no instance at $base."; break }
        Stop-Instance $pgBin
    }

    'remove' {
        if (Test-Path $data) { Stop-Instance $pgBin }
        if (Test-Path $base) {
            Remove-Item $base -Recurse -Force
            Write-Host "Removed $base."
        } else {
            Write-Host "Nothing to remove."
        }
    }

    'status' {
        if (-not (Test-Path $data)) {
            Write-Host "No instance at $data."
        } elseif (Test-Listening $Port) {
            Write-Host "Listening on port $Port. Data directory: $data"
        } else {
            Write-Host "Instance exists at $data but is not listening on port $Port."
        }
    }
}
