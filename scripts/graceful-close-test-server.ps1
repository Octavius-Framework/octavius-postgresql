<#
.SYNOPSIS
    Stands up a throwaway PostgreSQL that closes its sockets gracefully on Windows, for the session
    termination tests.

.DESCRIPTION
    A backend that ends a session sends a FATAL and exits. On Windows, PostgreSQL leaves the socket for the
    kernel to close, which it does abortively: the client is sent a reset, and Winsock discards whatever the
    client had received but not yet read. A FATAL that ends an idle session is gone by the time anything reads
    it, whatever the client does, so on a Windows server the tests skip those cases - unless
    `TEST_GRACEFUL_CLOSE=true` says the server is one like this.

    The server-side fix - an explicit shutdown() and closesocket() at backend exit - went into PostgreSQL in
    6051857fc and ed52c3707 and was reverted in 29992a6a5, because walreceivers then missed the FD_CLOSE of a
    graceful close and hung. a8458f508 has since fixed those lost events, but the revert still stands and no
    release has the fix, so this needs a build that does:
    -Bin points at that build's bin directory. The install is copied into `.graceful-close-test/` at the
    repository root, beside the data directory, so the instance keeps working after the build it came from is
    rebuilt for something else. It listens on a port of its own, the everyday database on 5432 is not touched,
    and `remove` really does remove all of it.

    On Linux and macOS the ordinary server already closes gracefully, and none of this is needed.

.PARAMETER Action
    start   Create the instance if needed, then start it and report how to run the tests.
    stop    Stop the server, keeping the data directory for next time.
    remove  Stop the server and delete `.graceful-close-test/` outright.
    status  Report whether the instance exists and whether it is listening.

.PARAMETER Bin
    The bin directory of a PostgreSQL 18 or newer build that closes gracefully. Needed only when the instance
    is created.

.PARAMETER Port
    Port to listen on. Defaults to 5434, to stay clear of an ordinary install on 5432 and the TLS server on 5433.

.PARAMETER Password
    Password for the `postgres` role. Defaults to the one the tests use.

.EXAMPLE
    .\scripts\graceful-close-test-server.ps1 start -Bin D:\postgresql\b\tmp_install\usr\local\pgsql\bin

.EXAMPLE
    .\scripts\graceful-close-test-server.ps1 remove
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('start', 'stop', 'remove', 'status')]
    [string]$Action = 'start',

    [string]$Bin,

    [int]$Port = 5434,

    [string]$Password = '1234'
)

$ErrorActionPreference = 'Stop'

$root     = Split-Path -Parent $PSScriptRoot
$base     = Join-Path $root '.graceful-close-test'
$install  = Join-Path $base 'pgsql'
$ownBin   = Join-Path $install 'bin'
$dataDir  = Join-Path $base 'data'
$logFile  = Join-Path $base 'server.log'

function Test-Listening {
    try {
        $client = [System.Net.Sockets.TcpClient]::new()
        $connect = $client.BeginConnect('localhost', $Port, $null, $null)
        $ok = $connect.AsyncWaitHandle.WaitOne(500)
        if ($ok) { $client.EndConnect($connect) }
        $client.Close()
        return $ok
    } catch {
        return $false
    }
}

function New-Instance {
    if (-not $Bin) {
        throw "No instance at $base yet. Pass -Bin with the bin directory of a build that closes gracefully."
    }
    if (-not (Test-Path (Join-Path $Bin 'initdb.exe'))) {
        throw "No initdb.exe in $Bin."
    }

    $version = (& (Join-Path $Bin 'initdb.exe') --version) -replace '^.*?(\d+).*$', '$1'
    if ([int]$version -lt 18) {
        Write-Warning "Found PostgreSQL $version. Octavius requires 18 or newer, so most tests will fail."
    }

    New-Item -ItemType Directory -Force -Path $base | Out-Null

    # bin, lib and share are what a running server reads; debug symbols are left behind, they are most of
    # the size of a debug build and nothing here needs them.
    $source = Split-Path -Parent $Bin
    Write-Host "Copying the install from $source"
    foreach ($dir in 'bin', 'lib', 'share') {
        $from = Join-Path $source $dir
        if (Test-Path $from) {
            robocopy $from (Join-Path $install $dir) /E /XF *.pdb /NFL /NDL /NJH /NJS /NP | Out-Null
            if ($LASTEXITCODE -ge 8) { throw "Copying $from failed (robocopy exit code $LASTEXITCODE)." }
        }
    }

    $pwFile = Join-Path $base 'pwfile.txt'
    Set-Content -Path $pwFile -Value $Password -NoNewline

    Write-Host "Creating data directory at $dataDir"
    & (Join-Path $ownBin 'initdb.exe') -D $dataDir -U postgres --auth=scram-sha-256 --pwfile=$pwFile -E UTF8 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "initdb failed with exit code $LASTEXITCODE." }

    Remove-Item $pwFile -Force

    Add-Content -Path (Join-Path $dataDir 'postgresql.conf') -Value @"

# --- added by scripts/graceful-close-test-server.ps1 ---
port = $Port
listen_addresses = 'localhost'
"@
}

function Start-Instance {
    if (Test-Listening) {
        Write-Host "Already listening on port $Port."
        return
    }

    Write-Host "Starting server on port $Port"
    Start-Process -FilePath (Join-Path $ownBin 'pg_ctl.exe') `
        -ArgumentList @('-D', $dataDir, '-l', $logFile, 'start') `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $base 'pg_ctl.out') `
        -RedirectStandardError (Join-Path $base 'pg_ctl.err')

    $ready = $false
    foreach ($attempt in 1..30) {
        Start-Sleep -Seconds 1
        & (Join-Path $ownBin 'pg_isready.exe') -h localhost -p $Port -q 2>$null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    }

    if (-not $ready) {
        throw "Server did not become ready. See $logFile."
    }

    $env:PGPASSWORD = $Password
    $exists = & (Join-Path $ownBin 'psql.exe') -h localhost -p $Port -U postgres -d postgres -tAc `
        "SELECT 1 FROM pg_database WHERE datname = 'octavius_test'"
    if (-not $exists) {
        & (Join-Path $ownBin 'createdb.exe') -h localhost -p $Port -U postgres octavius_test
        Write-Host "Created database octavius_test."
    }
}

function Write-TestInstructions {
    Write-Host ""
    Write-Host "Ready. To run the session termination tests against it:" -ForegroundColor Green
    Write-Host ""
    Write-Host "  `$env:TEST_DATABASE_PORT = `"$Port`""
    Write-Host "  `$env:TEST_GRACEFUL_CLOSE = `"true`""
    Write-Host "  .\gradlew.bat :driver:test --tests `"*SessionTerminatedExceptionIntegrationTest*`""
    Write-Host ""
    Write-Host "And when you are done, so it is not left running:"
    Write-Host "  Remove-Item Env:TEST_DATABASE_PORT, Env:TEST_GRACEFUL_CLOSE"
    Write-Host "  .\scripts\graceful-close-test-server.ps1 remove"
    Write-Host ""
}

function Stop-Instance {
    if (-not (Test-Path (Join-Path $dataDir 'postmaster.pid'))) {
        Write-Host "Not running."
        return
    }

    & (Join-Path $ownBin 'pg_ctl.exe') -D $dataDir -m fast stop | Out-Null
    Write-Host "Stopped."
}

switch ($Action) {
    'start' {
        if (-not (Test-Path $dataDir)) { New-Instance }
        Start-Instance
        Write-TestInstructions
    }

    'stop' {
        if (-not (Test-Path $dataDir)) { Write-Host "Nothing to stop - no instance at $base."; break }
        Stop-Instance
    }

    'remove' {
        if (Test-Path $dataDir) { Stop-Instance }
        if (Test-Path $base) {
            Remove-Item $base -Recurse -Force
            Write-Host "Removed $base."
        } else {
            Write-Host "Nothing to remove."
        }
    }

    'status' {
        if (-not (Test-Path $dataDir)) {
            Write-Host "No instance at $base."
        } elseif (Test-Listening) {
            Write-Host "Listening on port $Port. Data directory: $dataDir"
        } else {
            Write-Host "Instance exists at $dataDir but is not listening on port $Port."
        }
    }
}
