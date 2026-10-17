# Notification state is separate from the financial ledger: suppressing a retry
# must never prevent the workbook processor from attempting it again.
function Remove-HermesRepeatedDeferrals {
    param(
        [string]$Inbox,
        [string]$StatePath,
        [AllowEmptyCollection()][object[]]$Items,
        [Collections.IDictionary]$Counts,
        [switch]$DryRun
    )
    if($DryRun){return $Items}
    $path="$StatePath.notification-state.json"
    $previous=@{}
    if(Test-Path -LiteralPath $path -PathType Leaf){
        try{
            $saved=Get-Content -LiteralPath $path -Raw | ConvertFrom-Json -AsHashtable
            if($saved.schemaVersion -eq 1 -and $saved.deferrals -is [Collections.IDictionary]){$previous=$saved.deferrals}
        }catch{
            # A missing/corrupt notification cache permits one new notification;
            # it cannot invalidate a durable financial operation.
        }
    }
    $changed=$false
    $visible=[Collections.Generic.List[object]]::new()
    foreach($item in $Items){
        # Corrections track deferrals alongside their immutable operations.
        if(-not $item.fileName -or $item.fileName -clike 'expense_correction_*.json'){$visible.Add($item);continue}
        $key=[string]$item.fileName
        if($item.outcome -cne 'deferred'){
            if($previous.Contains($key)){$previous.Remove($key);$changed=$true}
            $visible.Add($item);continue
        }
        $source=Join-Path $Inbox $key
        if(-not (Test-Path -LiteralPath $source -PathType Leaf)){$visible.Add($item);continue}
        $identity=[ordered]@{fileSha256=(Get-HermesFileSha256 $source);reasonCode=[string]$item.reasonCode;messageSha256=(Get-HermesTextSha256 ([string]$item.message))}
        if($previous.Contains($key) -and (Test-HermesPayloadEqual $previous[$key] $identity)){
            $Counts.deferred=[Math]::Max(0,([int]$Counts.deferred-1))
            $Counts.discovered=[Math]::Max(0,([int]$Counts.discovered-1))
            continue
        }
        $previous[$key]=$identity;$changed=$true
        $visible.Add($item)
    }
    foreach($key in @($previous.Keys)){
        if(-not (Test-Path -LiteralPath (Join-Path $Inbox $key) -PathType Leaf)){$previous.Remove($key);$changed=$true}
    }
    if($changed){Write-HermesAtomicJson $path ([ordered]@{schemaVersion=1;deferrals=$previous})}
    return $visible.ToArray()
}
