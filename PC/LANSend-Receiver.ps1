param([switch]$Startup)

Add-Type -AssemblyName PresentationFramework, PresentationCore, WindowsBase, System.Windows.Forms, System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

# Give the PowerShell-hosted WPF window its own Windows taskbar identity.
# Without this, Windows may group it under powershell.exe and show PowerShell's icon.
Add-Type -TypeDefinition @'
using System.Runtime.InteropServices;

public static class LanSendTaskbarIdentity {
    [DllImport("shell32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern int SetCurrentProcessExplicitAppUserModelID(string appId);
}
'@
[void][LanSendTaskbarIdentity]::SetCurrentProcessExplicitAppUserModelID('LANSend.PC.3')

$Port = 8734
$AppFolder = Join-Path $env:LOCALAPPDATA 'PocketDrop'
$LogoPath = Join-Path $PSScriptRoot 'LAN-Send-Logo.png'
$IconPath = Join-Path $PSScriptRoot 'LAN-Send.ico'
$ConfigPath = Join-Path $AppFolder 'config.json'
$Inbox = Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'LAN Send Inbox'
$StartupShortcut = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\Startup\LAN Send Receiver.lnk'
$LegacyStartupShortcut = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\Startup\PocketDrop Receiver.lnk'
$LauncherPath = Join-Path $PSScriptRoot 'LANSend-Launcher.vbs'

New-Item -ItemType Directory -Path $AppFolder,$Inbox -Force | Out-Null

function Test-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    (New-Object Security.Principal.WindowsPrincipal($identity)).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Initialize-NetworkAccess {
    $reservation = netsh http show urlacl "url=http://+:$Port/" 2>$null | Out-String
    if ($reservation -notmatch [regex]::Escape("http://+:$Port/")) {
        if (-not (Test-Administrator)) {
            $args = "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`""
            Start-Process powershell.exe -Verb RunAs -ArgumentList $args
            exit
        }
        $user = [Security.Principal.WindowsIdentity]::GetCurrent().Name
        netsh http add urlacl "url=http://+:$Port/" "user=$user" | Out-Null
        netsh advfirewall firewall add rule name="LAN Send Receiver" dir=in action=allow protocol=TCP localport=$Port profile=private | Out-Null
    }
}

function Set-StartupShortcut {
    $shell = New-Object -ComObject WScript.Shell
    $shortcut = $shell.CreateShortcut($StartupShortcut)
    if (Test-Path $LauncherPath) {
        $shortcut.TargetPath = Join-Path $env:WINDIR 'System32\wscript.exe'
        $shortcut.Arguments = "`"$LauncherPath`" Startup"
        $shortcut.IconLocation = "$IconPath,0"
    } else {
        # Portable fallback for copies that do not include the launcher yet.
        $shortcut.TargetPath = 'powershell.exe'
        $shortcut.Arguments = "-WindowStyle Hidden -NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" -Startup"
    }
    $shortcut.WorkingDirectory = $PSScriptRoot
    $shortcut.Description = 'Start LAN Send in the notification area'
    $shortcut.Save()
}

Initialize-NetworkAccess

if (Test-Path $ConfigPath) {
    $Config = Get-Content $ConfigPath -Raw | ConvertFrom-Json
} else {
    $Config = [pscustomobject]@{ Token = ([guid]::NewGuid().ToString('N')); Inbox = $Inbox; PhoneAddress = ''; PhoneToken = ''; PcDeviceId = ([guid]::NewGuid().ToString('N')); PairedPhoneId = ''; AutoReceiveFiles = $true }
    $Config | ConvertTo-Json | Set-Content $ConfigPath -Encoding UTF8
}
if (-not $Config.PSObject.Properties['PhoneAddress']) { $Config | Add-Member NoteProperty PhoneAddress '' }
if (-not $Config.PSObject.Properties['PhoneToken']) { $Config | Add-Member NoteProperty PhoneToken '' }
if (-not $Config.PSObject.Properties['PcDeviceId']) { $Config | Add-Member NoteProperty PcDeviceId ([guid]::NewGuid().ToString('N')) }
if (-not $Config.PSObject.Properties['PairedPhoneId']) { $Config | Add-Member NoteProperty PairedPhoneId '' }
if (-not $Config.PSObject.Properties['AutoReceiveFiles']) { $Config | Add-Member NoteProperty AutoReceiveFiles $true }
$Config | ConvertTo-Json | Set-Content $ConfigPath -Encoding UTF8
$script:Token = [string]$Config.Token
$Inbox = [string]$Config.Inbox
New-Item -ItemType Directory -Path $Inbox -Force | Out-Null

function Get-LocalAddress {
    $route = Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue |
        Sort-Object RouteMetric, InterfaceMetric | Select-Object -First 1
    $ip = if ($route) {
        Get-NetIPAddress -InterfaceIndex $route.InterfaceIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue |
            Where-Object { $_.IPAddress -notlike '169.254.*' } |
            Select-Object -First 1 -ExpandProperty IPAddress
    }
    if ($ip) { "http://${ip}:$Port" } else { "http://YOUR-PC-IP:$Port" }
}

$Address = Get-LocalAddress

# Small self-contained QR encoder (fixed QR version 5-L, enough for PocketDrop pairing).
# This keeps the private pairing key on this PC instead of sending it to an online QR service.
Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Drawing;

public static class PocketDropQr {
    const int Size = 37;
    static readonly bool[,] modules = new bool[Size, Size];
    static readonly bool[,] function = new bool[Size, Size];

    public static Bitmap Create(string text, int scale) {
        Array.Clear(modules, 0, modules.Length);
        Array.Clear(function, 0, function.Length);
        DrawFinder(3, 3); DrawFinder(Size - 4, 3); DrawFinder(3, Size - 4);
        for (int i = 8; i < Size - 8; i++) {
            SetFunction(i, 6, i % 2 == 0); SetFunction(6, i, i % 2 == 0);
        }
        DrawAlignment(30, 30);
        SetFunction(8, Size - 8, true);
        DrawFormatBits();

        byte[] codewords = MakeCodewords(text);
        int bitIndex = 0;
        for (int right = Size - 1; right >= 1; right -= 2) {
            if (right == 6) right--;
            bool upward = ((right + 1) & 2) == 0;
            for (int vert = 0; vert < Size; vert++) {
                int y = upward ? Size - 1 - vert : vert;
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    if (function[x, y]) continue;
                    bool bit = bitIndex < codewords.Length * 8 &&
                        ((codewords[bitIndex >> 3] >> (7 - (bitIndex & 7))) & 1) != 0;
                    bitIndex++;
                    if (((x + y) & 1) == 0) bit = !bit; // Mask pattern 0
                    modules[x, y] = bit;
                }
            }
        }

        int border = 4;
        Bitmap bmp = new Bitmap((Size + border * 2) * scale, (Size + border * 2) * scale);
        using (Graphics g = Graphics.FromImage(bmp)) {
            g.Clear(Color.White);
            using (Brush brush = new SolidBrush(Color.FromArgb(32, 33, 36))) {
                for (int y = 0; y < Size; y++) for (int x = 0; x < Size; x++)
                    if (modules[x, y]) g.FillRectangle(brush, (x + border) * scale, (y + border) * scale, scale, scale);
            }
        }
        return bmp;
    }

    static byte[] MakeCodewords(string text) {
        byte[] payload = System.Text.Encoding.UTF8.GetBytes(text);
        if (payload.Length > 106) throw new ArgumentException("PocketDrop pairing data is too long");
        List<bool> bits = new List<bool>();
        AppendBits(bits, 4, 4); AppendBits(bits, payload.Length, 8);
        foreach (byte b in payload) AppendBits(bits, b, 8);
        int capacity = 108 * 8;
        for (int i = 0; i < 4 && bits.Count < capacity; i++) bits.Add(false);
        while ((bits.Count & 7) != 0) bits.Add(false);
        List<byte> data = new List<byte>();
        for (int i = 0; i < bits.Count; i += 8) {
            int val = 0; for (int j = 0; j < 8; j++) val = (val << 1) | (bits[i + j] ? 1 : 0);
            data.Add((byte)val);
        }
        bool toggle = true;
        while (data.Count < 108) { data.Add(toggle ? (byte)0xEC : (byte)0x11); toggle = !toggle; }
        byte[] ecc = ReedSolomon(data.ToArray(), 26);
        byte[] all = new byte[134]; data.CopyTo(all, 0); ecc.CopyTo(all, 108); return all;
    }

    static void AppendBits(List<bool> bits, int value, int count) {
        for (int i = count - 1; i >= 0; i--) bits.Add(((value >> i) & 1) != 0);
    }

    static byte[] ReedSolomon(byte[] data, int degree) {
        byte[] gen = new byte[] { 1 };
        int root = 1;
        for (int i = 0; i < degree; i++) {
            byte[] next = new byte[gen.Length + 1];
            for (int j = 0; j < gen.Length; j++) {
                next[j] ^= gen[j]; next[j + 1] ^= Multiply(gen[j], (byte)root);
            }
            gen = next; root = Multiply((byte)root, 2);
        }
        byte[] rem = new byte[degree];
        foreach (byte b in data) {
            byte factor = (byte)(b ^ rem[0]);
            for (int i = 0; i < degree - 1; i++) rem[i] = rem[i + 1];
            rem[degree - 1] = 0;
            for (int i = 0; i < degree; i++) rem[i] ^= Multiply(gen[i + 1], factor);
        }
        return rem;
    }

    static byte Multiply(byte x, byte y) {
        int z = 0, a = x, b = y;
        while (b != 0) { if ((b & 1) != 0) z ^= a; b >>= 1; a = (a << 1) ^ ((a >> 7) * 0x11D); }
        return (byte)z;
    }

    static void DrawFinder(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) for (int dx = -4; dx <= 4; dx++) {
            int x = cx + dx, y = cy + dy;
            if (x >= 0 && x < Size && y >= 0 && y < Size) {
                int d = Math.Max(Math.Abs(dx), Math.Abs(dy)); SetFunction(x, y, d != 2 && d != 4);
            }
        }
    }

    static void DrawAlignment(int cx, int cy) {
        for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++)
            SetFunction(cx + dx, cy + dy, Math.Max(Math.Abs(dx), Math.Abs(dy)) != 1);
    }

    static void DrawFormatBits() {
        int data = 8, rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ (((rem >> 9) & 1) * 0x537);
        int bits = ((data << 10) | rem) ^ 0x5412;
        Func<int, bool> bit = i => ((bits >> i) & 1) != 0;
        for (int i = 0; i <= 5; i++) SetFunction(8, i, bit(i));
        SetFunction(8, 7, bit(6)); SetFunction(8, 8, bit(7)); SetFunction(7, 8, bit(8));
        for (int i = 9; i < 15; i++) SetFunction(14 - i, 8, bit(i));
        for (int i = 0; i < 8; i++) SetFunction(Size - 1 - i, 8, bit(i));
        for (int i = 8; i < 15; i++) SetFunction(8, Size - 15 + i, bit(i));
        SetFunction(8, Size - 8, true);
    }

    static void SetFunction(int x, int y, bool black) { modules[x, y] = black; function[x, y] = true; }
}
'@ -ReferencedAssemblies System.Drawing

$QrPath = Join-Path $AppFolder 'pairing-qr.png'
function Update-PairingQr {
    $payload = "pocketdrop|$Address|$script:Token|$($Config.PcDeviceId)"
    $bitmap = [PocketDropQr]::Create($payload, 6)
    $bitmap.Save($QrPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $bitmap.Dispose()
    if ($script:QrImage) {
        $source = New-Object Windows.Media.Imaging.BitmapImage
        $source.BeginInit(); $source.CacheOption = 'OnLoad'; $source.UriSource = [Uri]$QrPath; $source.EndInit()
        $script:QrImage.Source = $source
    }
}
Update-PairingQr

function Get-MaskedToken {
    if ($script:Token.Length -le 8) { return ('*' * $script:Token.Length) }
    return "$($script:Token.Substring(0,4))$([char]0x2022)$([char]0x2022)$([char]0x2022)$([char]0x2022)$([char]0x2022)$([char]0x2022)$([char]0x2022)$([char]0x2022)$($script:Token.Substring($script:Token.Length - 4))"
}

$xaml = @"
<Window xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
        xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml"
        Title="LAN Send PC" Width="780" Height="850" MinWidth="700" MinHeight="720"
        WindowStartupLocation="CenterScreen" Background="#F5F7FC" FontFamily="Segoe UI"
        ResizeMode="CanResize" AllowDrop="True">
  <Window.Resources>
    <Style TargetType="Button">
      <Setter Property="Foreground" Value="White"/><Setter Property="Background" Value="#4664F5"/>
      <Setter Property="BorderThickness" Value="0"/><Setter Property="Padding" Value="16,9"/>
      <Setter Property="FontSize" Value="13"/><Setter Property="FontWeight" Value="SemiBold"/>
      <Setter Property="Cursor" Value="Hand"/>
      <Setter Property="Template">
        <Setter.Value><ControlTemplate TargetType="Button">
          <Border x:Name="ButtonBorder" Background="{TemplateBinding Background}" CornerRadius="9" Padding="{TemplateBinding Padding}">
            <ContentPresenter HorizontalAlignment="Center" VerticalAlignment="Center"/>
          </Border>
          <ControlTemplate.Triggers>
            <Trigger Property="IsMouseOver" Value="True"><Setter TargetName="ButtonBorder" Property="Opacity" Value="0.88"/></Trigger>
            <Trigger Property="IsPressed" Value="True"><Setter TargetName="ButtonBorder" Property="Opacity" Value="0.72"/></Trigger>
            <Trigger Property="IsEnabled" Value="False"><Setter TargetName="ButtonBorder" Property="Opacity" Value="0.4"/></Trigger>
          </ControlTemplate.Triggers>
        </ControlTemplate></Setter.Value>
      </Setter>
    </Style>
    <Style x:Key="SoftButton" TargetType="Button" BasedOn="{StaticResource {x:Type Button}}">
      <Setter Property="Foreground" Value="#3048C9"/><Setter Property="Background" Value="#EEF1FF"/>
    </Style>
    <Style TargetType="TextBox">
      <Setter Property="BorderBrush" Value="#DCE2EE"/><Setter Property="BorderThickness" Value="1"/>
      <Setter Property="Background" Value="#F9FAFD"/><Setter Property="Foreground" Value="#182033"/>
      <Setter Property="Padding" Value="10,8"/><Setter Property="FontSize" Value="13"/>
    </Style>
    <Style x:Key="Card" TargetType="Border">
      <Setter Property="Background" Value="White"/><Setter Property="CornerRadius" Value="16"/>
      <Setter Property="BorderBrush" Value="#E1E6F0"/><Setter Property="BorderThickness" Value="1"/>
      <Setter Property="Padding" Value="18"/>
    </Style>
  </Window.Resources>

  <Grid Margin="24,20,24,18">
    <Grid.RowDefinitions>
      <RowDefinition Height="Auto"/><RowDefinition Height="Auto"/><RowDefinition Height="Auto"/>
      <RowDefinition Height="Auto"/><RowDefinition Height="*"/><RowDefinition Height="Auto"/>
    </Grid.RowDefinitions>

    <Grid>
      <Grid.ColumnDefinitions><ColumnDefinition Width="52"/><ColumnDefinition Width="*"/></Grid.ColumnDefinitions>
      <Border Width="46" Height="46" CornerRadius="13" Background="#4664F5" VerticalAlignment="Center" ClipToBounds="True">
        <Image Name="LogoImage" Stretch="UniformToFill"/>
      </Border>
      <StackPanel Grid.Column="1" Margin="12,0,0,0" VerticalAlignment="Center">
        <TextBlock Text="LAN Send" FontSize="27" FontWeight="SemiBold" Foreground="#182033"/>
        <TextBlock Text="Private transfers over local Wi-Fi" Foreground="#758096" FontSize="13"/>
      </StackPanel>
    </Grid>

    <Border Grid.Row="1" Style="{StaticResource Card}" Margin="0,18,0,0">
      <Grid>
        <Grid.ColumnDefinitions><ColumnDefinition Width="170"/><ColumnDefinition Width="*"/></Grid.ColumnDefinitions>
        <Border Background="#F7F8FC" CornerRadius="12" Padding="8" BorderBrush="#E1E6F0" BorderThickness="1">
          <Image Name="QrImage" Width="150" Height="150" Stretch="Uniform"/>
        </Border>
        <Grid Grid.Column="1" Margin="20,1,0,0">
          <Grid.RowDefinitions><RowDefinition Height="Auto"/><RowDefinition Height="Auto"/><RowDefinition Height="Auto"/><RowDefinition Height="Auto"/></Grid.RowDefinitions>
          <TextBlock Text="CONNECT YOUR PHONE" FontSize="11" FontWeight="Bold" Foreground="#4664F5"/>
          <TextBlock Grid.Row="1" Margin="0,5,0,12" Text="Open LAN Send on your phone and scan this QR code."
                     TextWrapping="Wrap" Foreground="#536078" FontSize="13"/>
          <Border Grid.Row="2" Background="#F7F8FC" CornerRadius="9" Padding="11,8" Margin="0,0,0,8">
            <StackPanel><TextBlock Text="PC ADDRESS" FontSize="9" FontWeight="Bold" Foreground="#8792A8"/>
              <TextBox Name="AddressBox" Margin="-10,0" Padding="10,2" FontSize="14" IsReadOnly="True" BorderThickness="0" Background="Transparent"/></StackPanel>
          </Border>
          <Border Grid.Row="3" Background="#F7F8FC" CornerRadius="9" Padding="11,8">
            <StackPanel><TextBlock Text="PRIVATE KEY" FontSize="9" FontWeight="Bold" Foreground="#8792A8"/>
              <TextBox Name="TokenBox" Margin="-10,0" Padding="10,2" FontSize="12" IsReadOnly="True" BorderThickness="0" Background="Transparent"/></StackPanel>
          </Border>
        </Grid>
      </Grid>
    </Border>

    <Grid Grid.Row="2" Margin="0,12,0,12">
      <Grid.ColumnDefinitions><ColumnDefinition Width="Auto"/><ColumnDefinition Width="Auto"/><ColumnDefinition Width="Auto"/><ColumnDefinition Width="*"/></Grid.ColumnDefinitions>
      <Button Name="CopyButton" Content="Copy setup" Width="112" Height="38"/>
      <Button Name="FolderButton" Grid.Column="1" Style="{StaticResource SoftButton}" Content="Open inbox" Width="112" Height="38" Margin="8,0,0,0"/>
      <Button Name="SettingsButton" Grid.Column="2" Style="{StaticResource SoftButton}" Content="Settings" Width="96" Height="38" Margin="8,0,0,0"/>
      <CheckBox Name="StartupBox" Grid.Column="3" Content="Start with Windows" VerticalAlignment="Center" HorizontalAlignment="Right"
                Foreground="#536078" Margin="16,0,2,0"/>
    </Grid>

    <Border Grid.Row="3" Style="{StaticResource Card}" Margin="0,0,0,12">
      <Grid>
        <Grid.RowDefinitions><RowDefinition Height="Auto"/><RowDefinition Height="Auto"/><RowDefinition Height="Auto"/><RowDefinition Height="Auto"/></Grid.RowDefinitions>
        <Grid><Grid.ColumnDefinitions><ColumnDefinition Width="*"/><ColumnDefinition Width="Auto"/></Grid.ColumnDefinitions>
          <TextBlock Text="Send to phone" FontSize="18" FontWeight="SemiBold" Foreground="#182033"/>
          <Border Grid.Column="1" Background="#FFF7E6" CornerRadius="9" Padding="10,5">
            <TextBlock Name="PhoneStatus" Foreground="#A66500" FontSize="11" FontWeight="SemiBold" Text="Phone not connected"/>
          </Border>
        </Grid>
        <TextBox Name="PhoneMessageBox" Grid.Row="1" Height="66" Margin="0,13,0,10" AcceptsReturn="True"
                 TextWrapping="Wrap" VerticalScrollBarVisibility="Auto" ToolTip="Type a message or link"/>
        <Grid Grid.Row="2"><Grid.ColumnDefinitions><ColumnDefinition Width="Auto"/><ColumnDefinition Width="Auto"/><ColumnDefinition Width="*"/></Grid.ColumnDefinitions>
          <Button Name="SendPhoneMessageButton" Content="Send message" Width="126" Height="38"/>
          <Button Name="SendPhoneFileButton" Grid.Column="1" Style="{StaticResource SoftButton}" Content="Choose file" Width="112" Height="38" Margin="8,0,0,0"/>
          <TextBlock Grid.Column="2" HorizontalAlignment="Right" VerticalAlignment="Center" Foreground="#8792A8"
                     FontSize="12" Text="You can also drop files anywhere here"/>
        </Grid>
        <ProgressBar Name="PhoneSendProgress" Grid.Row="3" Height="7" Margin="0,12,0,0"
                     Minimum="0" Maximum="100" Visibility="Collapsed"/>
      </Grid>
    </Border>

    <Border Grid.Row="4" Style="{StaticResource Card}" Padding="18,15,18,12">
      <Grid><Grid.RowDefinitions><RowDefinition Height="Auto"/><RowDefinition Height="*"/></Grid.RowDefinitions>
        <Grid><Grid.ColumnDefinitions><ColumnDefinition Width="*"/><ColumnDefinition Width="Auto"/></Grid.ColumnDefinitions>
          <TextBlock Text="Transfer activity" FontSize="18" FontWeight="SemiBold" Foreground="#182033" VerticalAlignment="Center"/>
          <Button Name="ClearHistoryButton" Grid.Column="1" Style="{StaticResource SoftButton}" Content="Clear" Width="68" Height="32" Padding="10,5"/>
        </Grid>
        <Border Grid.Row="1" Margin="0,11,0,0" Background="#F7F8FC" CornerRadius="10" Padding="8">
          <ListBox Name="HistoryList" BorderThickness="0" Background="Transparent" Foreground="#364158"
                   FontSize="13" Padding="4" ScrollViewer.VerticalScrollBarVisibility="Auto"/>
        </Border>
      </Grid>
    </Border>

    <Border Grid.Row="5" Margin="0,12,0,0" Background="#EDF9F2" CornerRadius="10" Padding="12,8">
      <TextBlock Name="StatusText" Text="Listening - ready to receive" Foreground="#16834B" FontWeight="SemiBold" FontSize="12"/>
    </Border>
  </Grid>
</Window>
"@

$reader = New-Object System.Xml.XmlNodeReader ([xml]$xaml)
$window = [Windows.Markup.XamlReader]::Load($reader)
$logoImage = $window.FindName('LogoImage')
if (Test-Path $LogoPath) {
    $logoSource = New-Object Windows.Media.Imaging.BitmapImage
    $logoSource.BeginInit(); $logoSource.CacheOption = 'OnLoad'; $logoSource.UriSource = [Uri]$LogoPath; $logoSource.EndInit()
    $logoImage.Source = $logoSource
}
if (Test-Path $IconPath) { $window.Icon = [Windows.Media.Imaging.BitmapFrame]::Create([Uri]$IconPath) }
$addressBox = $window.FindName('AddressBox'); $addressBox.Text = $Address
$tokenBox = $window.FindName('TokenBox'); $tokenBox.Text = Get-MaskedToken
$qrImage = $window.FindName('QrImage')
$script:QrImage = $qrImage
$qrSource = New-Object Windows.Media.Imaging.BitmapImage
$qrSource.BeginInit(); $qrSource.CacheOption = 'OnLoad'; $qrSource.UriSource = [Uri]$QrPath; $qrSource.EndInit()
$qrImage.Source = $qrSource
$historyList = $window.FindName('HistoryList')
$clearHistoryButton = $window.FindName('ClearHistoryButton')
$clearHistoryButton.add_Click({
    $answer = [System.Windows.MessageBox]::Show(
        'This removes the activity history only. Your transferred files will not be deleted.',
        'Clear transfer activity?',
        [System.Windows.MessageBoxButton]::YesNo,
        [System.Windows.MessageBoxImage]::Warning
    )
    if ($answer -eq [System.Windows.MessageBoxResult]::Yes) {
        $historyList.Items.Clear()
        $window.FindName('StatusText').Text = 'Transfer activity cleared'
    }
})
$phoneStatus = $window.FindName('PhoneStatus')
if ($Config.PhoneAddress) { $phoneStatus.Text = "Connected: $($Config.PhoneAddress)"; $phoneStatus.Foreground = '#2E7D32' }
$legacyStartupWasEnabled = Test-Path $LegacyStartupShortcut
if ($legacyStartupWasEnabled -and -not (Test-Path $StartupShortcut)) {
    Set-StartupShortcut
    Remove-Item $LegacyStartupShortcut -Force -ErrorAction SilentlyContinue
}
# Refresh an existing shortcut after script/file renames.
if (Test-Path $StartupShortcut) {
    Set-StartupShortcut
}
$startupBox = $window.FindName('StartupBox'); $startupBox.IsChecked = Test-Path $StartupShortcut

$notify = New-Object System.Windows.Forms.NotifyIcon
$notify.Icon = if (Test-Path $IconPath) { New-Object System.Drawing.Icon($IconPath) } else { [System.Drawing.SystemIcons]::Information }
$notify.Text = 'LAN Send Receiver'
$notify.Visible = $true
$notify.add_DoubleClick({ $window.Show(); $window.WindowState = 'Normal'; $window.Activate() })
$script:ArrivalOpenPath = ''
$notify.add_BalloonTipClicked({
    if ($script:ArrivalOpenPath -and (Test-Path -LiteralPath $script:ArrivalOpenPath -PathType Leaf)) {
        Start-Process -FilePath $script:ArrivalOpenPath
    }
})

$menu = New-Object System.Windows.Forms.ContextMenuStrip
[void]$menu.Items.Add('Open LAN Send', $null, { $window.Show(); $window.WindowState = 'Normal'; $window.Activate() })
[void]$menu.Items.Add('Open Inbox', $null, { Start-Process explorer.exe $Inbox })
[void]$menu.Items.Add('Exit', $null, { $script:AllowClose = $true; $window.Close() })
$notify.ContextMenuStrip = $menu

function Add-History([string]$text) {
    $window.Dispatcher.Invoke([action]{
        $historyList.Items.Insert(0, "$(Get-Date -Format 'HH:mm')  $text")
        while ($historyList.Items.Count -gt 30) { $historyList.Items.RemoveAt(30) }
    })
}

function Show-Arrival([string]$title, [string]$body, [string]$OpenPath = '') {
    $script:ArrivalOpenPath = $OpenPath
    $notify.BalloonTipTitle = $title
    $notify.BalloonTipText = $body
    $notify.ShowBalloonTip(3500)
}

function Save-InboxSetting([string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path)) { return }
    New-Item -ItemType Directory -Path $Path -Force | Out-Null
    $script:Inbox = $Path
    $Config.Inbox = $Path
    $Config | ConvertTo-Json | Set-Content $ConfigPath -Encoding UTF8
    $window.FindName('StatusText').Text = "Inbox changed to $Path"
}

function Reset-PairingSecurity([string]$StatusMessage) {
    $script:Token = [guid]::NewGuid().ToString('N')
    $Config.Token = $script:Token
    $Config.PhoneAddress = ''
    $Config.PhoneToken = ''
    $Config.PairedPhoneId = ''
    $Config | ConvertTo-Json | Set-Content $ConfigPath -Encoding UTF8
    $tokenBox.Text = Get-MaskedToken
    Update-PairingQr
    $phoneStatus.Text = 'Phone not connected'
    $phoneStatus.Foreground = '#A66500'
    $window.FindName('StatusText').Text = $StatusMessage
}

function Show-SettingsWindow {
    $settingsXaml = @"
<Window xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
        xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml"
        Title="LAN Send settings" Width="570" Height="555" ResizeMode="NoResize"
        WindowStartupLocation="CenterOwner" Background="#F5F7FC" FontFamily="Segoe UI" ShowInTaskbar="False">
  <Window.Resources>
    <Style TargetType="Button">
      <Setter Property="Foreground" Value="#3048C9"/><Setter Property="Background" Value="#EEF1FF"/>
      <Setter Property="BorderThickness" Value="0"/><Setter Property="Padding" Value="14,8"/>
      <Setter Property="FontSize" Value="13"/><Setter Property="FontWeight" Value="SemiBold"/>
      <Setter Property="Cursor" Value="Hand"/>
      <Setter Property="Template">
        <Setter.Value><ControlTemplate TargetType="Button">
          <Border x:Name="ButtonBorder" Background="{TemplateBinding Background}" CornerRadius="9" Padding="{TemplateBinding Padding}">
            <ContentPresenter HorizontalAlignment="Center" VerticalAlignment="Center"/>
          </Border>
          <ControlTemplate.Triggers>
            <Trigger Property="IsMouseOver" Value="True"><Setter TargetName="ButtonBorder" Property="Opacity" Value="0.86"/></Trigger>
            <Trigger Property="IsPressed" Value="True"><Setter TargetName="ButtonBorder" Property="Opacity" Value="0.70"/></Trigger>
          </ControlTemplate.Triggers>
        </ControlTemplate></Setter.Value>
      </Setter>
    </Style>
  </Window.Resources>
  <Grid Margin="24">
    <Grid.RowDefinitions><RowDefinition Height="Auto"/><RowDefinition Height="Auto"/><RowDefinition Height="Auto"/><RowDefinition Height="Auto"/><RowDefinition Height="Auto"/><RowDefinition Height="Auto"/><RowDefinition Height="Auto"/><RowDefinition Height="Auto"/><RowDefinition Height="Auto"/><RowDefinition Height="Auto"/><RowDefinition Height="Auto"/><RowDefinition Height="Auto"/><RowDefinition Height="*"/></Grid.RowDefinitions>
    <TextBlock Text="PC inbox folder" FontSize="21" FontWeight="SemiBold" Foreground="#182033"/>
    <TextBlock Grid.Row="1" Margin="0,7,0,14" Text="Files received from your phone are saved here." Foreground="#758096" FontSize="13"/>
    <TextBox Name="InboxPathBox" Grid.Row="2" Height="42" IsReadOnly="True" VerticalContentAlignment="Center"
             Padding="10,0" BorderBrush="#DCE2EE" Background="White" Foreground="#364158"/>
    <TextBlock Name="SavedText" Grid.Row="3" Margin="2,10,0,0" Text="" Foreground="#16834B" FontSize="12" FontWeight="SemiBold"/>
    <StackPanel Grid.Row="4" Margin="0,13,0,0" Orientation="Horizontal" HorizontalAlignment="Right">
      <Button Name="RestoreButton" Content="Restore default" Width="120" Height="38" Margin="0,0,8,0"/>
      <Button Name="OpenButton" Content="Open folder" Width="105" Height="38" Margin="0,0,8,0"/>
      <Button Name="ChooseButton" Content="Change folder" Width="115" Height="38" Background="#4664F5" Foreground="White"/>
    </StackPanel>
    <Border Grid.Row="5" Height="1" Background="#DCE2EE" Margin="0,20,0,16"/>
    <TextBlock Grid.Row="6" Text="Receiving" FontSize="18" FontWeight="SemiBold" Foreground="#182033"/>
    <TextBlock Grid.Row="7" Margin="0,5,0,0" Text="Choose whether your paired phone may send files directly to this inbox."
               TextWrapping="Wrap" Foreground="#758096" FontSize="13"/>
    <CheckBox Name="AutoReceiveBox" Grid.Row="8" Margin="0,12,0,0" Content="Automatically receive files from paired phone"
              Foreground="#364158" FontSize="13"/>
    <Border Grid.Row="9" Height="1" Background="#DCE2EE" Margin="0,18,0,16"/>
    <TextBlock Grid.Row="10" Text="Security" FontSize="18" FontWeight="SemiBold" Foreground="#182033"/>
    <TextBlock Grid.Row="11" Margin="0,5,0,0" Text="Manage the phone paired with this PC. Security changes require scanning the QR code again."
               TextWrapping="Wrap" Foreground="#758096" FontSize="13"/>
    <StackPanel Grid.Row="12" Margin="0,14,0,0" Orientation="Horizontal" HorizontalAlignment="Right">
      <Button Name="ForgetPhoneButton" Content="Forget paired phone" Width="165" Height="38" Margin="0,0,8,0" Foreground="#B42318" Background="#FFF0EE"/>
      <Button Name="RegenerateKeyButton" Content="Regenerate private key" Width="165" Height="38" Foreground="White" Background="#4664F5"/>
    </StackPanel>
  </Grid>
</Window>
"@
    $settingsReader = New-Object System.Xml.XmlNodeReader ([xml]$settingsXaml)
    $settingsWindow = [Windows.Markup.XamlReader]::Load($settingsReader)
    $settingsWindow.Owner = $window
    $pathBox = $settingsWindow.FindName('InboxPathBox')
    $savedText = $settingsWindow.FindName('SavedText')
    $pathBox.Text = $script:Inbox
    $autoReceiveBox = $settingsWindow.FindName('AutoReceiveBox')
    $autoReceiveBox.IsChecked = [bool]$Config.AutoReceiveFiles
    $autoReceiveBox.add_Click({
        $Config.AutoReceiveFiles = [bool]$autoReceiveBox.IsChecked
        $Config | ConvertTo-Json | Set-Content $ConfigPath -Encoding UTF8
        $savedText.Text = if ($Config.AutoReceiveFiles) { 'Automatic file receiving enabled' } else { 'Automatic file receiving disabled' }
    })

    $settingsWindow.FindName('ChooseButton').add_Click({
        # OpenFileDialog uses the modern Explorer picker. With validation disabled,
        # its placeholder filename lets the user select the folder currently open.
        $picker = New-Object Microsoft.Win32.OpenFileDialog
        $picker.Title = 'Choose the LAN Send inbox folder'
        $picker.InitialDirectory = $script:Inbox
        $picker.CheckFileExists = $false
        $picker.CheckPathExists = $true
        $picker.ValidateNames = $false
        $picker.FileName = 'Select this folder'
        $picker.Filter = 'Folder|*.folder'
        if ($picker.ShowDialog($settingsWindow)) {
            $selectedFolder = Split-Path -Parent $picker.FileName
            Save-InboxSetting $selectedFolder
            $pathBox.Text = $script:Inbox
            $savedText.Text = 'Folder updated'
        }
    })
    $settingsWindow.FindName('OpenButton').add_Click({ Start-Process explorer.exe $script:Inbox })
    $settingsWindow.FindName('RestoreButton').add_Click({
        $defaultInbox = Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'LAN Send Inbox'
        Save-InboxSetting $defaultInbox
        $pathBox.Text = $script:Inbox
        $savedText.Text = 'Default folder restored'
    })
    $settingsWindow.FindName('ForgetPhoneButton').add_Click({
        $answer = [System.Windows.MessageBox]::Show(
            'This revokes the current phone connection and creates a new private key. You will need to scan the updated QR code again. Transferred files will not be deleted.',
            'Forget paired phone?',
            [System.Windows.MessageBoxButton]::YesNo,
            [System.Windows.MessageBoxImage]::Warning
        )
        if ($answer -eq [System.Windows.MessageBoxResult]::Yes) {
            Reset-PairingSecurity 'Paired phone forgotten - scan the new QR code to connect'
            $settingsWindow.Close()
        }
    })
    $settingsWindow.FindName('RegenerateKeyButton').add_Click({
        $answer = [System.Windows.MessageBox]::Show(
            'The current phone will stop connecting immediately. A new private key and QR code will be created. Continue?',
            'Regenerate private key?',
            [System.Windows.MessageBoxButton]::YesNo,
            [System.Windows.MessageBoxImage]::Warning
        )
        if ($answer -eq [System.Windows.MessageBoxResult]::Yes) {
            Reset-PairingSecurity 'Private key regenerated - scan the new QR code to reconnect'
            $settingsWindow.Close()
        }
    })
    [void]$settingsWindow.ShowDialog()
}

function Send-Response($context, [int]$code, [string]$text) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($text)
    $context.Response.StatusCode = $code
    $context.Response.ContentType = 'text/plain; charset=utf-8'
    $context.Response.ContentLength64 = $bytes.Length
    $context.Response.OutputStream.Write($bytes, 0, $bytes.Length)
    $context.Response.Close()
}

function Receive-Request($context) {
    try {
        if ($context.Request.HttpMethod -eq 'GET' -and $context.Request.Url.AbsolutePath -eq '/ping') {
            Send-Response $context 200 'LAN Send is ready'; return
        }
        if ($context.Request.Headers['X-PocketDrop-Token'] -ne $script:Token) {
            Send-Response $context 401 'Private key rejected'; return
        }
        $requestDeviceId = [string]$context.Request.Headers['X-PocketDrop-Device']
        if ($Config.PairedPhoneId -and $requestDeviceId -ne [string]$Config.PairedPhoneId) {
            Send-Response $context 403 'Paired device required'; return
        }
        if ($context.Request.HttpMethod -ne 'POST') { Send-Response $context 405 'POST required'; return }

        switch ($context.Request.Url.AbsolutePath) {
            '/api/register' {
                $reader = New-Object IO.StreamReader($context.Request.InputStream, [Text.Encoding]::UTF8)
                $registration = $reader.ReadToEnd(); $reader.Dispose()
                $parts = $registration -split '\|', 3
                if ($parts.Count -lt 2 -or $parts[0] -notmatch '^http://') { Send-Response $context 400 'Invalid phone registration'; return }
                $phoneDeviceId = if ($parts.Count -ge 3) { [string]$parts[2] } else { '' }
                if ($phoneDeviceId -and $requestDeviceId -and $phoneDeviceId -ne $requestDeviceId) { Send-Response $context 400 'Invalid device identity'; return }
                $Config.PhoneAddress = $parts[0].TrimEnd('/')
                $Config.PhoneToken = $parts[1]
                if ($phoneDeviceId) { $Config.PairedPhoneId = $phoneDeviceId }
                $Config | ConvertTo-Json | Set-Content $ConfigPath -Encoding UTF8
                $phoneStatus.Text = "Connected: $($Config.PhoneAddress)"; $phoneStatus.Foreground = '#2E7D32'
                Add-History 'Phone connected for two-way transfers'
                Send-Response $context 200 'OK'
            }
            '/api/text' {
                $reader = New-Object IO.StreamReader($context.Request.InputStream, [Text.Encoding]::UTF8)
                $text = $reader.ReadToEnd(); $reader.Dispose()
                if ([string]::IsNullOrWhiteSpace($text)) { Send-Response $context 400 'Empty message'; return }
                $stamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
                Add-Content -Path (Join-Path $Inbox 'Messages.txt') -Value "[$stamp] $text`r`n" -Encoding UTF8
                $window.Dispatcher.Invoke([action]{ [System.Windows.Clipboard]::SetText($text) })
                Add-History "Message: $($text.Substring(0, [Math]::Min(55, $text.Length)))"
                Show-Arrival 'LAN Send message' 'Copied to clipboard'
                Send-Response $context 200 'OK'
            }
            '/api/file' {
                if (-not [bool]$Config.AutoReceiveFiles) {
                    Send-Response $context 403 'Automatic file receiving is disabled on the PC.'; return
                }
                $encodedName = $context.Request.Headers['X-File-Name']
                $name = if ($encodedName) { [Uri]::UnescapeDataString($encodedName.Replace('+',' ')) } else { 'LANSend_file' }
                $name = [IO.Path]::GetFileName($name)
                foreach ($char in [IO.Path]::GetInvalidFileNameChars()) { $name = $name.Replace([string]$char, '_') }
                $target = Join-Path $Inbox $name
                $base = [IO.Path]::GetFileNameWithoutExtension($name); $ext = [IO.Path]::GetExtension($name)
                $copyNumber = 1
                while (Test-Path -LiteralPath $target) {
                    $target = Join-Path $Inbox "${base} ($copyNumber)${ext}"
                    $copyNumber++
                }
                $output = [IO.File]::Create($target)
                $context.Request.InputStream.CopyTo($output)
                $output.Dispose()
                Add-History "File: $([IO.Path]::GetFileName($target))"
                Show-Arrival 'LAN Send received - click to open' ([IO.Path]::GetFileName($target)) $target
                Send-Response $context 200 'OK'
            }
            default { Send-Response $context 404 'Unknown LAN Send endpoint' }
        }
    } catch {
        try { Send-Response $context 500 'LAN Send could not receive this item.' } catch {}
    }
}

function Send-ToPhone([string]$Path, [byte[]]$Bytes, [string]$ContentType, [string]$FileName = '') {
    if (-not $Config.PhoneAddress -or -not $Config.PhoneToken) { throw 'Connect the phone by scanning the PC QR code first.' }
    $request = [Net.HttpWebRequest]::Create("$($Config.PhoneAddress)$Path")
    $request.Method = 'POST'; $request.ContentType = $ContentType; $request.ContentLength = $Bytes.Length
    $request.Timeout = 30000; $request.ReadWriteTimeout = 30000
    $request.Headers.Add('X-PocketDrop-Token', [string]$Config.PhoneToken)
    $request.Headers.Add('X-PocketDrop-Device', [string]$Config.PcDeviceId)
    if ($FileName) { $request.Headers.Add('X-File-Name', [Uri]::EscapeDataString($FileName)) }
    $stream = $request.GetRequestStream(); $stream.Write($Bytes, 0, $Bytes.Length); $stream.Dispose()
    $response = $request.GetResponse(); $response.Dispose()
}

function Test-PhoneConnection {
    if (-not $Config.PhoneAddress -or -not $Config.PhoneToken) {
        $phoneStatus.Text = 'Phone not connected'
        $phoneStatus.Foreground = '#A66500'
        return
    }
    try {
        $request = [Net.HttpWebRequest]::Create("$($Config.PhoneAddress)/ping")
        $request.Method = 'GET'
        $request.Timeout = 1800
        $request.ReadWriteTimeout = 1800
        $request.Headers.Add('X-PocketDrop-Token', [string]$Config.PhoneToken)
        $request.Headers.Add('X-PocketDrop-Device', [string]$Config.PcDeviceId)
        $response = $request.GetResponse()
        $response.Dispose()
        $phoneStatus.Text = 'Phone online'
        $phoneStatus.Foreground = '#2E7D32'
    } catch {
        $phoneStatus.Text = 'Phone offline - open LAN Send'
        $phoneStatus.Foreground = '#A66500'
    }
}

function Show-FriendlySendError($ErrorRecord) {
    $message = if (-not $Config.PhoneAddress -or -not $Config.PhoneToken) {
        'Connect your phone by scanning the QR code first.'
    } elseif ($ErrorRecord.Exception -is [Net.WebException] -and
              $ErrorRecord.Exception.Response -and
              [int]$ErrorRecord.Exception.Response.StatusCode -eq 401) {
        'The phone rejected this connection. Open LAN Send on your phone and scan the QR code again.'
    } else {
        'Your phone appears to be offline. Open LAN Send on your phone and try again.'
    }
    $window.FindName('StatusText').Text = $message
    [System.Windows.MessageBox]::Show($message, 'LAN Send', [System.Windows.MessageBoxButton]::OK, [System.Windows.MessageBoxImage]::Information) | Out-Null
}

function Format-TransferSize([long]$Bytes) {
    if ($Bytes -lt 1KB) { return "$Bytes B" }
    if ($Bytes -lt 1MB) { return ('{0:N1} KB' -f ($Bytes / 1KB)) }
    if ($Bytes -lt 1GB) { return ('{0:N1} MB' -f ($Bytes / 1MB)) }
    return ('{0:N2} GB' -f ($Bytes / 1GB))
}

function Send-FileToPhone([string]$FilePath, [long]$CompletedBefore = 0, [long]$BatchTotal = 0) {
    if (-not (Test-Path $FilePath -PathType Leaf)) { return }
    try {
        $file = Get-Item -LiteralPath $FilePath
        if ($BatchTotal -le 0) { $BatchTotal = $file.Length }
        $progressBar = $window.FindName('PhoneSendProgress')
        $progressBar.Visibility = 'Visible'
        $progressBar.Value = if ($BatchTotal -gt 0) { ($CompletedBefore * 100.0 / $BatchTotal) } else { 0 }
        $window.FindName('StatusText').Text = "Sending $($file.Name)..."
        if (-not $Config.PhoneAddress -or -not $Config.PhoneToken) { throw 'Connect the phone by scanning the PC QR code first.' }
        $request = [Net.HttpWebRequest]::Create("$($Config.PhoneAddress)/api/file")
        $request.Method = 'POST'
        $request.ContentType = 'application/octet-stream'
        $request.ContentLength = $file.Length
        $request.Timeout = 120000
        $request.ReadWriteTimeout = 120000
        $request.AllowWriteStreamBuffering = $false
        $request.Headers.Add('X-PocketDrop-Token', [string]$Config.PhoneToken)
        $request.Headers.Add('X-PocketDrop-Device', [string]$Config.PcDeviceId)
        $request.Headers.Add('X-File-Name', [Uri]::EscapeDataString($file.Name))
        $input = [IO.File]::OpenRead($file.FullName)
        try {
            $stream = $request.GetRequestStream()
            try {
                $buffer = New-Object byte[] (64KB)
                [long]$sent = 0
                $lastUpdate = [Environment]::TickCount
                while (($count = $input.Read($buffer, 0, $buffer.Length)) -gt 0) {
                    $stream.Write($buffer, 0, $count)
                    $sent += $count
                    if ([Environment]::TickCount - $lastUpdate -ge 150) {
                        $overall = if ($BatchTotal -gt 0) { (($CompletedBefore + $sent) * 100.0 / $BatchTotal) } else { 0 }
                        $progressBar.Value = [Math]::Min(99, $overall)
                        $window.FindName('StatusText').Text = "Sending $($file.Name) - $(Format-TransferSize $sent) / $(Format-TransferSize $file.Length)"
                        $window.Dispatcher.Invoke([action]{}, [Windows.Threading.DispatcherPriority]::Background)
                        $lastUpdate = [Environment]::TickCount
                    }
                }
            } finally { $stream.Dispose() }
        } finally { $input.Dispose() }
        $response = $request.GetResponse(); $response.Dispose()
        Add-History "Sent to phone: $([IO.Path]::GetFileName($FilePath))"
        return $true
    } catch { Show-FriendlySendError $_; return $false }
}

function Send-FilesToPhone([string[]]$FilePaths) {
    $files = @($FilePaths | Where-Object { Test-Path $_ -PathType Leaf } | ForEach-Object { Get-Item -LiteralPath $_ })
    if ($files.Count -eq 0) { return }
    [long]$total = ($files | Measure-Object Length -Sum).Sum
    [long]$completed = 0
    foreach ($file in $files) {
        if (-not (Send-FileToPhone $file.FullName $completed $total)) { return }
        $completed += $file.Length
    }
    $window.FindName('PhoneSendProgress').Value = 100
    $window.FindName('StatusText').Text = if ($files.Count -eq 1) { 'File delivered to phone' } else { "$($files.Count) files delivered to phone" }
}

$listener = New-Object Net.HttpListener
$listener.Prefixes.Add("http://+:$Port/")
try { $listener.Start() } catch {
    [System.Windows.MessageBox]::Show('LAN Send could not start the PC receiver. Another copy may already be running. Close it from the notification area, then try again.', 'LAN Send', [System.Windows.MessageBoxButton]::OK, [System.Windows.MessageBoxImage]::Warning) | Out-Null
    $notify.Dispose(); exit
}

$pollTimer = New-Object Windows.Threading.DispatcherTimer
$pollTimer.Interval = [TimeSpan]::FromMilliseconds(180)
$script:PocketDropPendingRequest = $listener.GetContextAsync()
$pollTimer.add_Tick({
    try {
        if ($listener.IsListening -and $script:PocketDropPendingRequest.IsCompleted) {
            $context = $script:PocketDropPendingRequest.GetAwaiter().GetResult()
            $script:PocketDropPendingRequest = $listener.GetContextAsync()
            Receive-Request $context
        }
    } catch {}
})
$pollTimer.Start()

$heartbeatTimer = New-Object Windows.Threading.DispatcherTimer
$heartbeatTimer.Interval = [TimeSpan]::FromSeconds(5)
$heartbeatTimer.add_Tick({ Test-PhoneConnection })
$heartbeatTimer.Start()
Test-PhoneConnection

$window.FindName('CopyButton').add_Click({
    [System.Windows.Clipboard]::SetText("PC address: $Address`r`nPrivate key: $script:Token")
    $window.FindName('StatusText').Text = 'Setup copied'
})
$window.FindName('FolderButton').add_Click({ Start-Process explorer.exe $Inbox })
$window.FindName('SettingsButton').add_Click({ Show-SettingsWindow })
$window.FindName('SendPhoneMessageButton').add_Click({
    $text = $window.FindName('PhoneMessageBox').Text
    if ([string]::IsNullOrWhiteSpace($text)) { return }
    try {
        Send-ToPhone '/api/text' ([Text.Encoding]::UTF8.GetBytes($text)) 'text/plain; charset=utf-8'
        Add-History "Sent message: $($text.Substring(0, [Math]::Min(55, $text.Length)))"
        $window.FindName('PhoneMessageBox').Clear()
        $window.FindName('StatusText').Text = 'Message delivered to phone'
    } catch { Show-FriendlySendError $_ }
})
$window.FindName('SendPhoneFileButton').add_Click({
    $dialog = New-Object Microsoft.Win32.OpenFileDialog
    $dialog.Title = 'Choose a file to send to your phone'
    $dialog.Multiselect = $true
    if ($dialog.ShowDialog()) { Send-FilesToPhone $dialog.FileNames }
})
$window.add_PreviewDragOver({ param($sender, $e)
    if ($e.Data.GetDataPresent([Windows.DataFormats]::FileDrop)) {
        $e.Effects = [Windows.DragDropEffects]::Copy
        $window.FindName('StatusText').Text = 'Release to send file to phone'
    } else {
        $e.Effects = [Windows.DragDropEffects]::None
    }
    $e.Handled = $true
})
$window.add_PreviewDragLeave({
    $window.FindName('StatusText').Text = 'Listening - ready to receive'
})
$window.add_PreviewDrop({ param($sender, $e)
    if ($e.Data.GetDataPresent([Windows.DataFormats]::FileDrop)) {
        $files = @($e.Data.GetData([Windows.DataFormats]::FileDrop))
        Send-FilesToPhone ([string[]]$files)
    }
    $e.Handled = $true
})
$startupBox.add_Click({
    if ($startupBox.IsChecked) {
        Set-StartupShortcut
    } else { Remove-Item $StartupShortcut -Force -ErrorAction SilentlyContinue }
})

$script:AllowClose = $false
$window.add_Closing({ param($sender, $e)
    if (-not $script:AllowClose) { $e.Cancel = $true; $window.Hide() }
})
$window.add_Closed({ $heartbeatTimer.Stop(); $pollTimer.Stop(); $listener.Stop(); $listener.Close(); $notify.Visible = $false; $notify.Dispose() })
if ($Startup) { $window.add_ContentRendered({ $window.Hide() }) }
[void]$window.ShowDialog()
