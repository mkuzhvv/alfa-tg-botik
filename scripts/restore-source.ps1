param(
    [Parameter(Mandatory = $true)]
    [string]$BackupPath
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$backup = Get-Item -LiteralPath $BackupPath

if ($backup.PSIsContainer) {
    throw 'BackupPath must point to a backup file.'
}

$containerPath = '/tmp/alfa-source-' + [guid]::NewGuid().ToString('N') + '.backup'
$restoreLog = Join-Path $projectRoot 'data/source/restore-source.log'
$copyAttempted = $false

Push-Location $projectRoot

try {
    $objectCount = docker compose exec -T postgres psql -U postgres -d alfa_source -v ON_ERROR_STOP=1 -At -c "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname NOT LIKE 'pg_toast%' AND c.relkind IN ('r','p','v','m','S','f');" 2> $restoreLog
    if ($LASTEXITCODE -ne 0) {
        throw "Could not inspect alfa_source. Start PostgreSQL first. Local log: $restoreLog"
    }
    if (($objectCount -join '').Trim() -ne '0') {
        throw 'alfa_source is not empty. Restore stopped; existing data was not changed.'
    }

    $copyAttempted = $true
    docker compose cp $backup.FullName "postgres:$containerPath" *> $restoreLog
    if ($LASTEXITCODE -ne 0) {
        throw "Could not copy the backup into the local container. Local log: $restoreLog"
    }

    docker compose exec -T postgres pg_restore --list $containerPath *> $restoreLog
    if ($LASTEXITCODE -ne 0) {
        throw "The backup could not be read by pg_restore. Local log: $restoreLog"
    }

    docker compose exec -T postgres pg_restore --username=postgres --dbname=alfa_source --no-owner --no-privileges --single-transaction --exit-on-error $containerPath *> $restoreLog
    if ($LASTEXITCODE -ne 0) {
        throw "Restore failed and the transaction was rolled back. Local log: $restoreLog"
    }

    Write-Output 'Backup restored into alfa_source successfully.'
}
finally {
    if ($copyAttempted) {
        docker compose exec -T postgres rm -f -- $containerPath *> $null
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "Could not remove the temporary container file: $containerPath"
        }
    }
    Pop-Location
}
