param(
    [Parameter(Mandatory)] [string]$Serial,
    [string]$AdbPath = 'adb'
)

# Ejecutar desde la raíz del repositorio con las APK de depuración y pruebas ya generadas.
$ErrorActionPreference = 'Stop'

function Device { & $AdbPath -s $Serial @args }
function Ui([string]$Text, [string]$Class = '') {
    for ($attempt = 0; $attempt -lt 4; $attempt++) {
        Device shell uiautomator dump /data/local/tmp/sumi-t08-window.xml | Out-Null
        [xml]$tree = (Device shell cat /data/local/tmp/sumi-t08-window.xml | Out-String)
        $node = $tree.SelectNodes('//node') | Where-Object {
            (!$Text -or $_.GetAttribute('text') -ceq $Text) -and (!$Class -or $_.GetAttribute('class') -eq $Class)
        } | Select-Object -First 1
        if ($node) { return $node }
    }
    throw "No se encontró el control: $Text $Class"
}
function Tap($Node) {
    if ($Node.GetAttribute('bounds') -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { throw 'Coordenadas ausentes' }
    Device shell input tap ([int](([int]$Matches[1] + [int]$Matches[3]) / 2)) ([int](([int]$Matches[2] + [int]$Matches[4]) / 2))
}
function ChooseOrder([string]$Old, [string]$New) {
    Tap (Ui "Orden: $Old")
    Tap (Ui $New)
    $null = Ui "Orden: $New"
}
if ((Device shell getprop ro.kernel.qemu | Out-String).Trim() -ne '1') {
    throw 'Usá un emulador descartable para esta prueba.'
}
$original = (Device shell settings get global airplane_mode_on | Out-String).Trim()
try {
    Device install -r app/build/outputs/apk/debug/app-debug.apk
    if ($LASTEXITCODE -ne 0) { throw 'Install app failed' }
    Device install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    if ($LASTEXITCODE -ne 0) { throw 'Install tests failed' }
    Device shell cmd connectivity airplane-mode enable
    $offline = $false
    for ($i = 0; $i -lt 15; $i++) {
        if (Device shell dumpsys connectivity | Select-String 'Active default network: none') { $offline = $true; break }
        Start-Sleep -Seconds 1
    }
    if (!$offline) { throw 'Sin confirmación de desconexión' }
    $runner = 'com.agustin.sumi.test/com.agustin.sumi.data.local.document.SafDocumentSourceTest'
    $picker = Device shell am instrument -r -w -e phase picker $runner
    $picker
    if (($picker -join [Environment]::NewLine) -notmatch '6 tests passed') { throw 'Fallaron las regresiones del selector' }
    $recovery = Device shell am instrument -r -w -e phase recovery-picker $runner
    $recovery
    if (($recovery -join [Environment]::NewLine) -notmatch '3 tests passed') { throw 'Fallaron las regresiones de recuperación' }
    Device shell am force-stop com.agustin.sumi
    Device shell am start -W -n com.agustin.sumi/.MainActivity
    $az = 'A' + [char]0x2013 + 'Z'
    $za = 'Z' + [char]0x2013 + 'A'
    ChooseOrder 'Recientes' $az
    ChooseOrder $az $za
    $before = (Device shell pidof com.agustin.sumi | Out-String).Trim()
    Device shell am force-stop com.agustin.sumi
    Device shell am start -W -n com.agustin.sumi/.MainActivity
    $after = (Device shell pidof com.agustin.sumi | Out-String).Trim()
    if (!$after -or $before -eq $after) { throw 'Proceso nuevo no observado' }
    $null = Ui "Orden: $za"
    Write-Output 'PASS orden Z-A restaurado tras reinicio del proceso'
    ChooseOrder $za 'Recientes'
    Tap (Ui '' 'android.widget.EditText')
    Device shell input text 'ONe'
    Device shell input keyevent 4
    $null = Ui 'one'
    Write-Output 'PASS búsqueda con mayúsculas mixtas'
    Tap (Ui 'Quitar one de la biblioteca')
    Tap (Ui 'Quitar')
    $null = Ui 'Entrada quitada de la biblioteca.'
    $null = Ui 'No hay títulos que coincidan con tu búsqueda.'
    Tap (Ui 'Limpiar búsqueda')
    $null = Ui 'two'
    Write-Output 'PASS eliminación observable y limpieza de búsqueda'
} finally {
    if ($original -ne '1') { Device shell cmd connectivity airplane-mode disable }
}
