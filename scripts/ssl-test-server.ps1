<#
.SYNOPSIS
    Stands up throwaway PostgreSQL servers with TLS enabled, for the SSL and channel binding tests.

.DESCRIPTION
    The TLS-gated tests need a server with `ssl = on` and a certificate to bind to, which the
    everyday development database on 5432 generally is not. This puts one on a port of its own,
    entirely inside `.ssl-test/` at the repository root, so nothing about an existing install is
    touched and `remove` really does remove all of it.

    Beside it stands a server that differs from it only in its certificate, signed with RSASSA-PSS.
    Channel binding hashes the certificate with the digest its signature was made with, and a
    server presents one certificate, so each signature the tests bind to needs a server of its own.

    It is meant to be run for as long as the tests take and then thrown away - `remove` when you
    are done, so there are no servers left sitting in the background.

    On Linux and macOS the same servers are easier to get from Docker; the recipe CI uses is in
    `.github/workflows/tests.yml`, job `test-ssl`.

.PARAMETER Action
    start   Create whatever is missing, then start the servers and report how to run the tests.
    stop    Stop the servers, keeping the data directories and certificates for next time.
    remove  Stop the servers and delete `.ssl-test/` outright.
    status  Report whether each instance exists and whether it is listening.

.EXAMPLE
    .\scripts\ssl-test-server.ps1 start

.EXAMPLE
    .\scripts\ssl-test-server.ps1 remove
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('start', 'stop', 'remove', 'status')]
    [string]$Action = 'start'
)

$ErrorActionPreference = 'Stop'

# SslIntegrationTest and ChannelBindingIntegrationTest log in with this password and connect to the ports below,
# so none of them can change here alone.
$Password = '1234'

$root     = Split-Path -Parent $PSScriptRoot
$base     = Join-Path $root '.ssl-test'
$certDir  = Join-Path $base 'certs'

function New-Server {
    param([string]$Name, [int]$Port, [string]$Cert, [string]$Key)

    # The first server keeps the names it had before there were others, so an existing `.ssl-test/` stays usable.
    $suffix = if ($Name) { "-$Name" } else { '' }
    [pscustomobject]@{
        Name = if ($Name) { $Name } else { 'main' }
        Port = $Port
        Cert = $Cert
        Key  = $Key
        Data = Join-Path $base "data$suffix"
        Log  = Join-Path $base "server$suffix.log"
        Out  = Join-Path $base "pg_ctl$suffix.out"
        Err  = Join-Path $base "pg_ctl$suffix.err"
    }
}

# 5433 stays clear of an ordinary install on 5432. The servers after it differ from it only in the certificate
# they present.
$servers = @(
    New-Server -Port 5433 -Cert 'server.crt' -Key 'server.key'
    New-Server -Name 'rsapss' -Port 5436 -Cert 'server-rsapss.crt' -Key 'server.key'
)

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

function Find-OpenSsl {
    # Git's own copy is preferred over whatever else is on PATH: a Strawberry Perl install puts a
    # broken openssl.exe ahead of it for anyone who has one (its OPENSSLDIR is baked in from the
    # `subst`-ed drive it was built on, so `req` fails trying to open openssl.cnf) - and Git is
    # already a given in a repository that needs it for everything else.
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

function New-Certificates {
    $openssl = Find-OpenSsl
    New-Item -ItemType Directory -Force -Path $certDir | Out-Null

    Write-Host "Generating certificates with $openssl"

    # A CA of its own, so verify-ca and verify-full have something to verify against.
    & $openssl req -new -x509 -days 3650 -nodes `
        -out (Join-Path $certDir 'ca.crt') -keyout (Join-Path $certDir 'ca.key') `
        -subj '/CN=octavius-test-ca' 2>$null

    # CN=localhost is what verify-full checks the hostname against.
    & $openssl req -new -nodes `
        -out (Join-Path $certDir 'server.csr') -keyout (Join-Path $certDir 'server.key') `
        -subj '/CN=localhost' 2>$null
    & $openssl x509 -req -days 3650 -in (Join-Path $certDir 'server.csr') `
        -CA (Join-Path $certDir 'ca.crt') -CAkey (Join-Path $certDir 'ca.key') -CAcreateserial `
        -out (Join-Path $certDir 'server.crt') 2>$null

    # A client certificate too, so the client-auth test has one rather than skipping.
    & $openssl req -new -nodes `
        -out (Join-Path $certDir 'client.csr') -keyout (Join-Path $certDir 'client.key.tmp') `
        -subj '/CN=postgres' 2>$null
    & $openssl x509 -req -days 3650 -in (Join-Path $certDir 'client.csr') `
        -CA (Join-Path $certDir 'ca.crt') -CAkey (Join-Path $certDir 'ca.key') -CAcreateserial `
        -out (Join-Path $certDir 'client.crt') 2>$null
    # The JVM reads PKCS8 only, which is not what openssl writes by default.
    & $openssl pkcs8 -topk8 -inform PEM -outform PEM -nocrypt `
        -in (Join-Path $certDir 'client.key.tmp') -out (Join-Path $certDir 'client.key') 2>$null

    Remove-Item (Join-Path $certDir 'client.key.tmp') -Force -ErrorAction SilentlyContinue

    # A second client certificate on an elliptic-curve key. The driver reads the algorithm off the
    # certificate rather than assuming RSA, and this is the pair that proves it - nothing else here
    # is anything but RSA.
    & $openssl ecparam -name prime256v1 -genkey -noout -out (Join-Path $certDir 'client-ec.key.tmp') 2>$null
    & $openssl req -new -key (Join-Path $certDir 'client-ec.key.tmp') `
        -out (Join-Path $certDir 'client-ec.csr') -subj '/CN=postgres' 2>$null
    & $openssl x509 -req -days 3650 -in (Join-Path $certDir 'client-ec.csr') `
        -CA (Join-Path $certDir 'ca.crt') -CAkey (Join-Path $certDir 'ca.key') -CAcreateserial `
        -out (Join-Path $certDir 'client-ec.crt') 2>$null
    & $openssl pkcs8 -topk8 -inform PEM -outform PEM -nocrypt `
        -in (Join-Path $certDir 'client-ec.key.tmp') -out (Join-Path $certDir 'client-ec.key') 2>$null

    Remove-Item (Join-Path $certDir 'client-ec.key.tmp') -Force -ErrorAction SilentlyContinue

    # The RSA client key again, this time encrypted, for the sslpassword test. Same certificate as
    # client.crt - only the key file differs, which is the point: the driver has to unlock it.
    & $openssl pkcs8 -topk8 -inform PEM -outform PEM `
        -in (Join-Path $certDir 'client.key') -out (Join-Path $certDir 'client-enc.key') `
        -passout pass:senatus 2>$null

    if (-not (Test-Path (Join-Path $certDir 'server.crt'))) {
        throw "Certificate generation failed - no server.crt was produced."
    }
}

# The server certificate signed again, with RSASSA-PSS over SHA-384, for a server of its own. The name of that
# signature is the same whatever the digest, which is in its parameters - and channel binding has to hash with it.
# Made apart from the rest, so that an older `.ssl-test/` gains it without losing what it has.
function New-RsaPssCertificate {
    $openssl = Find-OpenSsl

    & $openssl x509 -req -days 3650 -in (Join-Path $certDir 'server.csr') `
        -CA (Join-Path $certDir 'ca.crt') -CAkey (Join-Path $certDir 'ca.key') -CAcreateserial `
        -sha384 -sigopt rsa_padding_mode:pss -sigopt rsa_pss_saltlen:digest `
        -out (Join-Path $certDir 'server-rsapss.crt') 2>$null

    if (-not (Test-Path (Join-Path $certDir 'server-rsapss.crt'))) {
        throw "Certificate generation failed - no server-rsapss.crt was produced."
    }
}

function New-Instance {
    param([string]$Bin, $Server)

    New-Item -ItemType Directory -Force -Path $base | Out-Null

    $version = (& (Join-Path $Bin 'initdb.exe') --version) -replace '[^\d.]', ''
    $major = [int]($version -split '\.')[0]
    if ($major -lt 18) {
        Write-Warning "Found PostgreSQL $version. Octavius requires 18 or newer, so most tests will fail."
    }

    $pwFile = Join-Path $base 'pwfile.txt'
    Set-Content -Path $pwFile -Value $Password -NoNewline

    Write-Host "Creating data directory at $($Server.Data)"
    & (Join-Path $Bin 'initdb.exe') -D $Server.Data -U postgres --auth=scram-sha-256 --pwfile=$pwFile -E UTF8 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "initdb failed with exit code $LASTEXITCODE." }

    Remove-Item $pwFile -Force

    # PostgreSQL wants the key beside its own files; on Windows it skips the permission check
    # that would otherwise reject a world-readable one.
    Copy-Item (Join-Path $certDir $Server.Cert), (Join-Path $certDir $Server.Key), (Join-Path $certDir 'ca.crt') `
        -Destination $Server.Data

    Add-Content -Path (Join-Path $Server.Data 'postgresql.conf') -Value @"

# --- added by scripts/ssl-test-server.ps1 ---
port = $($Server.Port)
listen_addresses = 'localhost'
ssl = on
ssl_cert_file = '$($Server.Cert)'
ssl_key_file = '$($Server.Key)'
ssl_ca_file = 'ca.crt'
"@

    if ($Server.Name -ne 'main') { return }

    # One role authenticating the way an ldap, pam or radius setup makes the server ask - with the
    # password in the clear. Rules go above the defaults because pg_hba is first-match, and both a
    # TLS and a plaintext line are here so the tests can check that the driver sends the password
    # over one and refuses over the other.
    $hba = Join-Path $Server.Data 'pg_hba.conf'
    $existing = Get-Content $hba -Raw
    Set-Content -Path $hba -Value @"
# --- added by scripts/ssl-test-server.ps1 ---
hostssl all cleartext_user 127.0.0.1/32 password
hostssl all cleartext_user ::1/128 password
host    all cleartext_user 127.0.0.1/32 password
host    all cleartext_user ::1/128 password

$existing
"@
}

function Start-Instance {
    param([string]$Bin, $Server)

    if (Test-Listening $Server.Port) {
        Write-Host "Already listening on port $($Server.Port)."
        return
    }

    Write-Host "Starting server on port $($Server.Port)"
    Start-Process -FilePath (Join-Path $Bin 'pg_ctl.exe') `
        -ArgumentList @('-D', $Server.Data, '-l', $Server.Log, 'start') `
        -WindowStyle Hidden `
        -RedirectStandardOutput $Server.Out `
        -RedirectStandardError $Server.Err

    $ready = $false
    foreach ($attempt in 1..30) {
        Start-Sleep -Seconds 1
        & (Join-Path $Bin 'pg_isready.exe') -h localhost -p $Server.Port -q 2>$null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    }

    if (-not $ready) {
        throw "Server did not become ready. See $($Server.Log)."
    }

    $env:PGPASSWORD = $Password
    $exists = & (Join-Path $Bin 'psql.exe') -h localhost -p $Server.Port -U postgres -d postgres -tAc `
        "SELECT 1 FROM pg_database WHERE datname = 'octavius_test'"
    if (-not $exists) {
        & (Join-Path $Bin 'createdb.exe') -h localhost -p $Server.Port -U postgres octavius_test
        Write-Host "Created database octavius_test."
    }

    if ($Server.Name -ne 'main') { return }

    $roleExists = & (Join-Path $Bin 'psql.exe') -h localhost -p $Server.Port -U postgres -d postgres -tAc `
        "SELECT 1 FROM pg_roles WHERE rolname = 'cleartext_user'"
    if (-not $roleExists) {
        & (Join-Path $Bin 'psql.exe') -h localhost -p $Server.Port -U postgres -d postgres -c `
            "CREATE ROLE cleartext_user LOGIN PASSWORD 'senatus'" | Out-Null
        Write-Host "Created role cleartext_user (cleartext password authentication)."
    }
}

function Write-TestInstructions {
    Write-Host ""
    Write-Host "Ready. To run the TLS-gated tests against them:" -ForegroundColor Green
    Write-Host ""
    Write-Host "  `$env:TEST_SSL = `"true`""
    Write-Host "  `$env:SSL_ROOT_CERT = `"$(Join-Path $certDir 'ca.crt')`""
    Write-Host "  `$env:SSL_CERT = `"$(Join-Path $certDir 'client.crt')`""
    Write-Host "  `$env:SSL_KEY = `"$(Join-Path $certDir 'client.key')`""
    Write-Host "  `$env:SSL_CERT_EC = `"$(Join-Path $certDir 'client-ec.crt')`""
    Write-Host "  `$env:SSL_KEY_EC = `"$(Join-Path $certDir 'client-ec.key')`""
    Write-Host "  `$env:SSL_KEY_ENCRYPTED = `"$(Join-Path $certDir 'client-enc.key')`""
    Write-Host "  `$env:SSL_KEY_PASSWORD = `"senatus`""
    Write-Host "  .\gradlew.bat :driver:test --tests `"*SslIntegrationTest*`" --tests `"*ChannelBindingIntegrationTest*`""
    Write-Host ""
    Write-Host "And when you are done, so it is not left running:"
    Write-Host "  .\scripts\ssl-test-server.ps1 remove"
    Write-Host ""
}

function Stop-Instance {
    param([string]$Bin, $Server)

    if (-not (Test-Path (Join-Path $Server.Data 'postmaster.pid'))) {
        Write-Host "Port $($Server.Port): not running."
        return
    }

    & (Join-Path $Bin 'pg_ctl.exe') -D $Server.Data -m fast stop | Out-Null
    Write-Host "Port $($Server.Port): stopped."
}

$bin = Find-PostgresBin

switch ($Action) {
    'start' {
        if (-not (Test-Path (Join-Path $certDir 'server.crt'))) { New-Certificates }
        if (-not (Test-Path (Join-Path $certDir 'server-rsapss.crt'))) { New-RsaPssCertificate }
        foreach ($server in $servers) {
            if (-not (Test-Path $server.Data)) { New-Instance -Bin $bin -Server $server }
            Start-Instance -Bin $bin -Server $server
        }
        Write-TestInstructions
    }

    'stop' {
        if (-not (Test-Path $base)) { Write-Host "Nothing to stop - no instance at $base."; break }
        foreach ($server in $servers) {
            if (Test-Path $server.Data) { Stop-Instance -Bin $bin -Server $server }
        }
    }

    'remove' {
        foreach ($server in $servers) {
            if (Test-Path $server.Data) { Stop-Instance -Bin $bin -Server $server }
        }
        if (Test-Path $base) {
            Remove-Item $base -Recurse -Force
            Write-Host "Removed $base."
        } else {
            Write-Host "Nothing to remove."
        }
    }

    'status' {
        foreach ($server in $servers) {
            if (-not (Test-Path $server.Data)) {
                Write-Host "No instance at $($server.Data)."
            } elseif (Test-Listening $server.Port) {
                Write-Host "Listening on port $($server.Port), presenting $($server.Cert). Data directory: $($server.Data)"
            } else {
                Write-Host "Instance exists at $($server.Data) but is not listening on port $($server.Port)."
            }
        }
    }
}
