<#
.SYNOPSIS
    Stands up a throwaway PostgreSQL from a build of your choosing, to run the suite against something other than
    the everyday server.

.DESCRIPTION
    The suite runs against localhost:5432 unless TEST_DATABASE_PORT names another port. This puts a server on one,
    from any PostgreSQL 18 or newer whose bin directory -Bin points at - a release candidate, a development build, a
    build carrying a server-side patch. Each instance lives in `.test-server/<name>/` at the repository root, with
    its own copy of the install beside the data directory, so it keeps working after the build it came from is
    rebuilt or uninstalled. Several can exist side by side under different names and ports, the everyday database
    on 5432 is not touched, and `remove` really does remove an instance.

    -GracefulClose marks a build that closes its sockets gracefully on Windows, and the instructions then set
    TEST_GRACEFUL_CLOSE as well. A stock PostgreSQL on Windows leaves the socket of an exiting backend for the
    kernel to close, which it does abortively: the reset that follows makes Winsock discard what the client had
    received but not read, a FATAL sent to an idle session among it. On a Windows server the tests of that case are
    therefore skipped, unless TEST_GRACEFUL_CLOSE says the server is not one of those. The fix - an explicit
    shutdown() and closesocket() at backend exit - went into PostgreSQL in 6051857fc and ed52c3707 and was reverted
    in 29992a6a5, because walreceivers then missed the FD_CLOSE of a graceful close and hung. a8458f508 has since
    fixed those lost events, but the revert still stands and no release has the fix.

.PARAMETER Action
    start   Create the instance if needed, then start it and report how to run the suite against it.
    stop    Stop the server, keeping the instance for next time.
    remove  Stop the server and delete the instance outright.
    status  Report whether the instance exists and is listening - every instance, when none is named.

.PARAMETER Name
    The instance, which is also its directory under `.test-server/`: letters, digits and hyphens.

.PARAMETER Bin
    The bin directory of the build to copy. Needed only when the instance is created.

.PARAMETER Port
    The port the instance listens on, chosen when it is created and remembered after that. Defaults to 5434, clear
    of an ordinary install on 5432 and the TLS server on 5433.

.PARAMETER GracefulClose
    Marks a build that closes its sockets gracefully on Windows. Given when the instance is created, and
    remembered after that.

.PARAMETER Password
    Password for the `postgres` role. Defaults to the one the tests use.

.EXAMPLE
    .\scripts\test-server.ps1 start pg19 -Bin "C:\Program Files\PostgreSQL\19\bin" -Port 5435

.EXAMPLE
    .\scripts\test-server.ps1 start graceful -Bin D:\postgresql\b\tmp_install\usr\local\pgsql\bin -GracefulClose

.EXAMPLE
    .\scripts\test-server.ps1 status

.EXAMPLE
    .\scripts\test-server.ps1 remove pg19
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('start', 'stop', 'remove', 'status')]
    [string]$Action = 'start',

    [Parameter(Position = 1)]
    [ValidatePattern('^[A-Za-z0-9-]+$')]
    [string]$Name,

    [string]$Bin,

    [int]$Port = 5434,

    [switch]$GracefulClose,

    [string]$Password = '1234'
)

$ErrorActionPreference = 'Stop'

$root    = Split-Path -Parent $PSScriptRoot
$servers = Join-Path $root '.test-server'

# What an instance is made of, and what it remembers about itself: the port and the marks it was created with
# are read back from instance.txt, so they are given once.
function Get-Instance {
    param([string]$InstanceName)

    $dir = Join-Path $servers $InstanceName
    $instance = [pscustomobject]@{
        Name     = $InstanceName
        Dir      = $dir
        Bin      = Join-Path $dir 'pgsql\bin'
        Data     = Join-Path $dir 'data'
        Log      = Join-Path $dir 'server.log'
        Record   = Join-Path $dir 'instance.txt'
        Port     = $Port
        Graceful = [bool]$GracefulClose
        Source   = $null
    }
    if (Test-Path $instance.Record) {
        foreach ($line in Get-Content $instance.Record) {
            $key, $value = $line -split '=', 2
            switch ($key) {
                'port'     { $instance.Port = [int]$value }
                'graceful' { $instance.Graceful = $value -eq 'true' }
                'source'   { $instance.Source = $value }
            }
        }
    }
    return $instance
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

function Test-Running {
    param($Instance)

    (Test-Path (Join-Path $Instance.Data 'postmaster.pid')) -and (Test-Listening $Instance.Port)
}

function New-Instance {
    param($Instance)

    if (-not $Bin) {
        throw "No instance '$($Instance.Name)' yet. Pass -Bin with the bin directory of the build to use."
    }
    if (-not (Test-Path (Join-Path $Bin 'initdb.exe'))) {
        throw "No initdb.exe in $Bin."
    }
    if (Test-Listening $Instance.Port) {
        throw "Something already listens on port $($Instance.Port). Pick another with -Port."
    }

    $version = (& (Join-Path $Bin 'initdb.exe') --version) -replace '^.*?(\d+).*$', '$1'
    if ([int]$version -lt 18) {
        Write-Warning "Found PostgreSQL $version. Octavius requires 18 or newer, so most tests will fail."
    }

    New-Item -ItemType Directory -Force -Path $Instance.Dir | Out-Null

    # bin, lib and share are what a running server reads; debug symbols are left behind, they are most of the
    # size of a debug build and nothing here needs them.
    $source = Split-Path -Parent $Bin
    Write-Host "Copying the install from $source"
    foreach ($dir in 'bin', 'lib', 'share') {
        $from = Join-Path $source $dir
        if (Test-Path $from) {
            robocopy $from (Join-Path $Instance.Dir "pgsql\$dir") /E /XF *.pdb /NFL /NDL /NJH /NJS /NP | Out-Null
            if ($LASTEXITCODE -ge 8) { throw "Copying $from failed (robocopy exit code $LASTEXITCODE)." }
        }
    }

    $pwFile = Join-Path $Instance.Dir 'pwfile.txt'
    Set-Content -Path $pwFile -Value $Password -NoNewline

    Write-Host "Creating data directory at $($Instance.Data)"
    & (Join-Path $Instance.Bin 'initdb.exe') -D $Instance.Data -U postgres --auth=scram-sha-256 --pwfile=$pwFile -E UTF8 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "initdb failed with exit code $LASTEXITCODE." }

    Remove-Item $pwFile -Force

    Add-Content -Path (Join-Path $Instance.Data 'postgresql.conf') -Value @"

# --- added by scripts/test-server.ps1 ---
port = $($Instance.Port)
listen_addresses = 'localhost'
"@

    Set-Content -Path $Instance.Record -Value @(
        "port=$($Instance.Port)",
        "graceful=$(if ($Instance.Graceful) { 'true' } else { 'false' })",
        "source=$Bin"
    )
    $Instance.Source = $Bin
}

function Start-Instance {
    param($Instance)

    if (Test-Running $Instance) {
        Write-Host "Instance '$($Instance.Name)' is already running on port $($Instance.Port)."
        return
    }
    if (Test-Listening $Instance.Port) {
        throw "Something else listens on port $($Instance.Port), which instance '$($Instance.Name)' was created with."
    }

    Write-Host "Starting '$($Instance.Name)' on port $($Instance.Port)"
    Start-Process -FilePath (Join-Path $Instance.Bin 'pg_ctl.exe') `
        -ArgumentList @('-D', $Instance.Data, '-l', $Instance.Log, 'start') `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $Instance.Dir 'pg_ctl.out') `
        -RedirectStandardError (Join-Path $Instance.Dir 'pg_ctl.err')

    $ready = $false
    foreach ($attempt in 1..30) {
        Start-Sleep -Seconds 1
        & (Join-Path $Instance.Bin 'pg_isready.exe') -h localhost -p $Instance.Port -q 2>$null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    }

    if (-not $ready) {
        throw "Server did not become ready. See $($Instance.Log)."
    }

    $env:PGPASSWORD = $Password
    $exists = & (Join-Path $Instance.Bin 'psql.exe') -h localhost -p $Instance.Port -U postgres -d postgres -tAc `
        "SELECT 1 FROM pg_database WHERE datname = 'octavius_test'"
    if (-not $exists) {
        & (Join-Path $Instance.Bin 'createdb.exe') -h localhost -p $Instance.Port -U postgres octavius_test
        Write-Host "Created database octavius_test."
    }
}

function Write-TestInstructions {
    param($Instance)

    $variables = @('Env:TEST_DATABASE_PORT')
    Write-Host ""
    Write-Host "Ready. To run the suite against it:" -ForegroundColor Green
    Write-Host ""
    Write-Host "  `$env:TEST_DATABASE_PORT = `"$($Instance.Port)`""
    if ($Instance.Graceful) {
        Write-Host "  `$env:TEST_GRACEFUL_CLOSE = `"true`""
        $variables += 'Env:TEST_GRACEFUL_CLOSE'
    }
    Write-Host "  .\gradlew.bat test"
    Write-Host ""
    Write-Host "And when you are done, so it is not left running:"
    Write-Host "  Remove-Item $($variables -join ', ')"
    Write-Host "  .\scripts\test-server.ps1 stop $($Instance.Name)"
    Write-Host ""
}

function Stop-Instance {
    param($Instance)

    if (-not (Test-Path (Join-Path $Instance.Data 'postmaster.pid'))) {
        Write-Host "'$($Instance.Name)' is not running."
        return
    }

    & (Join-Path $Instance.Bin 'pg_ctl.exe') -D $Instance.Data -m fast stop | Out-Null
    Write-Host "Stopped '$($Instance.Name)'."
}

function Write-Status {
    param($Instance)

    $graceful = if ($Instance.Graceful) { ', graceful close' } else { '' }
    $state = if (Test-Running $Instance) { 'running' } else { 'stopped' }
    Write-Host "$($Instance.Name): $state on port $($Instance.Port)$graceful - from $($Instance.Source)"
}

if (-not $Name -and $Action -ne 'status') {
    throw "Name the instance: .\scripts\test-server.ps1 $Action <name>"
}

switch ($Action) {
    'start' {
        $instance = Get-Instance $Name
        if (-not (Test-Path $instance.Data)) {
            New-Instance $instance
        } elseif ($Bin -or $PSBoundParameters.ContainsKey('Port') -or $GracefulClose) {
            Write-Warning ("'$Name' already exists and keeps what it was created with; -Bin, -Port and " +
                "-GracefulClose apply only to a new instance. Remove it first to make it anew.")
        }
        Start-Instance $instance
        Write-TestInstructions $instance
    }

    'stop' {
        $instance = Get-Instance $Name
        if (-not (Test-Path $instance.Data)) { Write-Host "Nothing to stop - no instance '$Name'."; break }
        Stop-Instance $instance
    }

    'remove' {
        $instance = Get-Instance $Name
        if (Test-Path $instance.Data) { Stop-Instance $instance }
        if (Test-Path $instance.Dir) {
            Remove-Item $instance.Dir -Recurse -Force
            Write-Host "Removed $($instance.Dir)."
        } else {
            Write-Host "Nothing to remove - no instance '$Name'."
        }
    }

    'status' {
        $names = if ($Name) { @($Name) } elseif (Test-Path $servers) {
            Get-ChildItem $servers -Directory | ForEach-Object { $_.Name }
        } else { @() }
        if (-not $names) { Write-Host "No instances under $servers." }
        foreach ($each in $names) {
            $instance = Get-Instance $each
            if (Test-Path $instance.Data) { Write-Status $instance } else { Write-Host "${each}: no such instance." }
        }
    }
}
