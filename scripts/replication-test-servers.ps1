<#
.SYNOPSIS
    Stands up a throwaway primary, a standby streaming from it and a server of another cluster, for the
    replication tests.

.DESCRIPTION
    ReplicationIntegrationTest chooses among servers that really are a primary and its standby, and passes
    over one that belongs to another cluster. This puts all three on ports of their own, entirely inside
    `.replication-test/` at the repository root, so nothing about an existing install is touched and
    `remove` really does remove all of it.

    The standby is a `pg_basebackup` of the primary, so it shares the primary's system identifier and
    replays whatever is done there. The third server is an `initdb` of its own, and so a cluster of its own.

    It is meant to be run for as long as the tests take and then thrown away - `remove` when you are done,
    so there are no three servers sitting in the background.

    On Linux and macOS the same servers are easier to get from Docker; the recipe CI uses is in
    `.github/workflows/tests.yml`, job `test-replication`.

.PARAMETER Action
    start   Create the instances if needed, then start them and report how to run the tests.
    stop    Stop the servers, keeping their data directories for next time.
    remove  Stop the servers and delete `.replication-test/` outright.
    status  Report whether each instance exists and whether it is listening.

.EXAMPLE
    .\scripts\replication-test-servers.ps1 start

.EXAMPLE
    .\scripts\replication-test-servers.ps1 remove
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('start', 'stop', 'remove', 'status')]
    [string]$Action = 'start'
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$base = Join-Path $root '.replication-test'

# ReplicationIntegrationTest logs in with this and connects to the ports below, so neither can change here alone.
$password = '1234'

# Started in this order and stopped in the reverse: the standby needs its primary to stream from.
$instances = @(
    [pscustomobject]@{ Name = 'primary'; Label = 'primary'; Port = 5442; Dir = Join-Path $base 'primary' },
    [pscustomobject]@{ Name = 'standby'; Label = 'standby'; Port = 5443; Dir = Join-Path $base 'standby' },
    [pscustomobject]@{ Name = 'foreign'; Label = 'server of another cluster'; Port = 5444; Dir = Join-Path $base 'foreign' }
)
$primary = $instances[0]
$standby = $instances[1]
$foreign = $instances[2]

function Find-PostgresBin {
    $onPath = Get-Command initdb -ErrorAction SilentlyContinue
    if ($onPath) { return Split-Path $onPath.Source }

    $installs = Get-ChildItem 'C:\Program Files\PostgreSQL' -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^\d+$' } |
        Sort-Object { [int]$_.Name } -Descending

    foreach ($install in $installs) {
        $bin = Join-Path $install.FullName 'bin'
        if (Test-Path (Join-Path $bin 'initdb.exe')) { return $bin }
    }

    throw "No PostgreSQL installation found. Put initdb on PATH, or install PostgreSQL 18 or newer."
}

function Test-Listening {
    param([int]$Port)

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

function Set-Port {
    param($Instance)

    # Appended, so a standby copied from the primary - this block included - ends on a port of its own.
    Add-Content -Path (Join-Path $Instance.Dir 'postgresql.conf') -Value @"

# --- added by scripts/replication-test-servers.ps1 ---
port = $($Instance.Port)
listen_addresses = 'localhost'
"@
}

function New-Cluster {
    param([string]$Bin, $Instance)

    $pwFile = Join-Path $base 'pwfile.txt'
    Set-Content -Path $pwFile -Value $password -NoNewline

    Write-Host "Creating the $($Instance.Label) at $($Instance.Dir)"
    # initdb lets replication in from localhost already, which is where the standby connects from.
    & (Join-Path $Bin 'initdb.exe') -D $Instance.Dir -U postgres --auth=scram-sha-256 --pwfile=$pwFile -E UTF8 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "initdb failed with exit code $LASTEXITCODE." }

    Remove-Item $pwFile -Force
    Set-Port -Instance $Instance
}

function New-Standby {
    param([string]$Bin)

    Write-Host "Copying the primary into a standby at $($standby.Dir)"
    # -R leaves standby.signal and the primary's address behind, so the copy starts as its standby.
    & (Join-Path $Bin 'pg_basebackup.exe') -h localhost -p $primary.Port -U postgres -D $standby.Dir -R -X stream
    if ($LASTEXITCODE -ne 0) { throw "pg_basebackup failed with exit code $LASTEXITCODE." }

    Set-Port -Instance $standby
}

function Start-Server {
    param([string]$Bin, $Instance)

    if (Test-Listening -Port $Instance.Port) {
        Write-Host "The $($Instance.Label) is already listening on port $($Instance.Port)."
        return
    }

    Write-Host "Starting the $($Instance.Label) on port $($Instance.Port)"
    Start-Process -FilePath (Join-Path $Bin 'pg_ctl.exe') `
        -ArgumentList @('-D', $Instance.Dir, '-l', (Join-Path $base "$($Instance.Name).log"), 'start') `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $base "$($Instance.Name).pg_ctl.out") `
        -RedirectStandardError (Join-Path $base "$($Instance.Name).pg_ctl.err")

    $ready = $false
    foreach ($attempt in 1..30) {
        Start-Sleep -Seconds 1
        & (Join-Path $Bin 'pg_isready.exe') -h localhost -p $Instance.Port -q 2>$null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    }

    if (-not $ready) {
        throw "The $($Instance.Label) did not become ready. See $(Join-Path $base "$($Instance.Name).log")."
    }
}

function New-TestDatabase {
    param([string]$Bin, $Instance)

    $exists = & (Join-Path $Bin 'psql.exe') -h localhost -p $Instance.Port -U postgres -d postgres -tAc `
        "SELECT 1 FROM pg_database WHERE datname = 'octavius_test'"
    if (-not $exists) {
        & (Join-Path $Bin 'createdb.exe') -h localhost -p $Instance.Port -U postgres octavius_test
        Write-Host "Created database octavius_test on the $($Instance.Label)."
    }
}

function Stop-Server {
    param([string]$Bin, $Instance)

    if (-not (Test-Path (Join-Path $Instance.Dir 'postmaster.pid'))) {
        Write-Host "The $($Instance.Label) is not running."
        return
    }

    & (Join-Path $Bin 'pg_ctl.exe') -D $Instance.Dir -m fast stop | Out-Null
    Write-Host "Stopped the $($Instance.Label)."
}

function Write-TestInstructions {
    Write-Host ""
    Write-Host "Ready. To run the replication tests against them:" -ForegroundColor Green
    Write-Host ""
    Write-Host "  `$env:TEST_REPLICATION = `"true`""
    Write-Host "  .\gradlew.bat :driver:test --tests `"*ReplicationIntegrationTest*`""
    Write-Host ""
    Write-Host "And when you are done, so they are not left running:"
    Write-Host "  .\scripts\replication-test-servers.ps1 remove"
    Write-Host ""
}

$bin = Find-PostgresBin
$env:PGPASSWORD = $password

switch ($Action) {
    'start' {
        New-Item -ItemType Directory -Force -Path $base | Out-Null

        $version = (& (Join-Path $bin 'initdb.exe') --version) -replace '[^\d.]', ''
        if ([int]($version -split '\.')[0] -lt 18) {
            Write-Warning "Found PostgreSQL $version. Octavius requires 18 or newer, so the tests will fail."
        }

        if (-not (Test-Path $primary.Dir)) { New-Cluster -Bin $bin -Instance $primary }
        Start-Server -Bin $bin -Instance $primary
        # Before the standby is copied, so it arrives there by replication like anything else would.
        New-TestDatabase -Bin $bin -Instance $primary

        if (-not (Test-Path $standby.Dir)) { New-Standby -Bin $bin }
        Start-Server -Bin $bin -Instance $standby

        if (-not (Test-Path $foreign.Dir)) { New-Cluster -Bin $bin -Instance $foreign }
        Start-Server -Bin $bin -Instance $foreign
        New-TestDatabase -Bin $bin -Instance $foreign

        Write-TestInstructions
    }

    'stop' {
        if (-not (Test-Path $base)) { Write-Host "Nothing to stop - no instances at $base."; break }
        foreach ($instance in @($standby, $primary, $foreign)) {
            if (Test-Path $instance.Dir) { Stop-Server -Bin $bin -Instance $instance }
        }
    }

    'remove' {
        foreach ($instance in @($standby, $primary, $foreign)) {
            if (Test-Path $instance.Dir) { Stop-Server -Bin $bin -Instance $instance }
        }
        if (Test-Path $base) {
            Remove-Item $base -Recurse -Force
            Write-Host "Removed $base."
        } else {
            Write-Host "Nothing to remove."
        }
    }

    'status' {
        foreach ($instance in $instances) {
            if (-not (Test-Path $instance.Dir)) {
                Write-Host "No $($instance.Label) at $($instance.Dir)."
            } elseif (Test-Listening -Port $instance.Port) {
                Write-Host "The $($instance.Label) is listening on port $($instance.Port)."
            } else {
                Write-Host "The $($instance.Label) exists at $($instance.Dir) but is not listening on port $($instance.Port)."
            }
        }
    }
}
