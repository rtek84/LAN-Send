param([switch]$Startup)

Add-Type -AssemblyName PresentationFramework, PresentationCore, WindowsBase, System.Windows.Forms, System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

$Port = 8734
$AppFolder = Join-Path $env:LOCALAPPDATA 'PocketDrop'
$ConfigPath = Join-Path $AppFolder 'config.json'
$Inbox = Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'PocketDrop Inbox'
$StartupShortcut = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\Startup\PocketDrop Receiver.lnk'

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
        netsh advfirewall firewall add rule name="PocketDrop Receiver" dir=in action=allow protocol=TCP localport=$Port profile=private | Out-Null
    }
}

Initialize-NetworkAccess

if (Test-Path $ConfigPath) {
    $Config = Get-Content $ConfigPath -Raw | ConvertFrom-Json
} else {
    $Config = [pscustomobject]@{ Token = ([guid]::NewGuid().ToString('N')); Inbox = $Inbox }
    $Config | ConvertTo-Json | Set-Content $ConfigPath -Encoding UTF8
}
$Token = [string]$Config.Token
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
$QrPayload = "pocketdrop|$Address|$Token"
$QrBitmap = [PocketDropQr]::Create($QrPayload, 6)
$QrBitmap.Save($QrPath, [System.Drawing.Imaging.ImageFormat]::Png)
$QrBitmap.Dispose()

$xaml = @"
<Window xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
        Title="PocketDrop Receiver" Width="720" Height="590" WindowStartupLocation="CenterScreen"
        Background="#F5F7FC" FontFamily="Segoe UI" ResizeMode="CanMinimize">
  <Grid Margin="26">
    <Grid.RowDefinitions>
      <RowDefinition Height="Auto"/><RowDefinition Height="Auto"/><RowDefinition Height="Auto"/>
      <RowDefinition Height="Auto"/><RowDefinition Height="*"/><RowDefinition Height="Auto"/>
    </Grid.RowDefinitions>
    <TextBlock Text="PocketDrop" FontSize="31" FontWeight="SemiBold" Foreground="#1F2937"/>
    <TextBlock Grid.Row="1" Margin="0,4,0,20" Text="Your private phone-to-desktop inbox" Foreground="#64748B" FontSize="14"/>
    <Border Grid.Row="2" Background="White" CornerRadius="14" Padding="18" BorderBrush="#E2E8F0" BorderThickness="1">
      <Grid>
        <Grid.ColumnDefinitions><ColumnDefinition Width="Auto"/><ColumnDefinition Width="*"/></Grid.ColumnDefinitions>
        <Border Background="White" CornerRadius="8" Padding="4" BorderBrush="#E2E8F0" BorderThickness="1">
          <Image Name="QrImage" Width="196" Height="196" Stretch="Uniform"/>
        </Border>
        <StackPanel Grid.Column="1" Margin="22,6,0,0">
          <TextBlock Text="SCAN WITH POCKETDROP" FontSize="11" FontWeight="Bold" Foreground="#536DFE"/>
          <TextBlock Margin="0,5,0,19" Text="Open PocketDrop on your phone and tap Scan desktop QR code." TextWrapping="Wrap" Foreground="#475569" FontSize="14"/>
          <TextBlock Text="DESKTOP ADDRESS" FontSize="10" FontWeight="Bold" Foreground="#94A3B8"/>
          <TextBox Name="AddressBox" Margin="0,3,0,12" FontSize="15" IsReadOnly="True" BorderThickness="0" Background="Transparent"/>
          <TextBlock Text="PRIVATE KEY" FontSize="10" FontWeight="Bold" Foreground="#94A3B8"/>
          <TextBox Name="TokenBox" Margin="0,3,0,0" FontSize="13" IsReadOnly="True" BorderThickness="0" Background="Transparent"/>
        </StackPanel>
      </Grid>
    </Border>
    <StackPanel Grid.Row="3" Orientation="Horizontal" Margin="0,14,0,14">
      <Button Name="CopyButton" Content="Copy setup" Width="110" Height="34" Margin="0,0,8,0"/>
      <Button Name="FolderButton" Content="Open inbox" Width="110" Height="34" Margin="0,0,8,0"/>
      <CheckBox Name="StartupBox" Content="Start with Windows" VerticalAlignment="Center" Margin="8,0,0,0"/>
    </StackPanel>
    <GroupBox Grid.Row="4" Header="Recent arrivals" Background="White" Padding="8">
      <ListBox Name="HistoryList" BorderThickness="0" Background="White"/>
    </GroupBox>
    <TextBlock Name="StatusText" Grid.Row="5" Margin="0,14,0,0" Text="Listening - ready to receive" Foreground="#2E7D32" FontWeight="SemiBold"/>
  </Grid>
</Window>
"@

$reader = New-Object System.Xml.XmlNodeReader ([xml]$xaml)
$window = [Windows.Markup.XamlReader]::Load($reader)
$addressBox = $window.FindName('AddressBox'); $addressBox.Text = $Address
$tokenBox = $window.FindName('TokenBox'); $tokenBox.Text = $Token
$qrImage = $window.FindName('QrImage')
$qrSource = New-Object Windows.Media.Imaging.BitmapImage
$qrSource.BeginInit(); $qrSource.CacheOption = 'OnLoad'; $qrSource.UriSource = [Uri]$QrPath; $qrSource.EndInit()
$qrImage.Source = $qrSource
$historyList = $window.FindName('HistoryList')
$startupBox = $window.FindName('StartupBox'); $startupBox.IsChecked = Test-Path $StartupShortcut

$notify = New-Object System.Windows.Forms.NotifyIcon
$notify.Icon = [System.Drawing.SystemIcons]::Information
$notify.Text = 'PocketDrop Receiver'
$notify.Visible = $true
$notify.add_DoubleClick({ $window.Show(); $window.WindowState = 'Normal'; $window.Activate() })

$menu = New-Object System.Windows.Forms.ContextMenuStrip
[void]$menu.Items.Add('Open PocketDrop', $null, { $window.Show(); $window.WindowState = 'Normal'; $window.Activate() })
[void]$menu.Items.Add('Open Inbox', $null, { Start-Process explorer.exe $Inbox })
[void]$menu.Items.Add('Exit', $null, { $script:AllowClose = $true; $window.Close() })
$notify.ContextMenuStrip = $menu

function Add-History([string]$text) {
    $window.Dispatcher.Invoke([action]{
        $historyList.Items.Insert(0, "$(Get-Date -Format 'HH:mm')  $text")
        while ($historyList.Items.Count -gt 30) { $historyList.Items.RemoveAt(30) }
    })
}

function Show-Arrival([string]$title, [string]$body) {
    $notify.BalloonTipTitle = $title
    $notify.BalloonTipText = $body
    $notify.ShowBalloonTip(3500)
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
            Send-Response $context 200 'PocketDrop is ready'; return
        }
        if ($context.Request.Headers['X-PocketDrop-Token'] -ne $Token) {
            Send-Response $context 401 'Private key rejected'; return
        }
        if ($context.Request.HttpMethod -ne 'POST') { Send-Response $context 405 'POST required'; return }

        switch ($context.Request.Url.AbsolutePath) {
            '/api/text' {
                $reader = New-Object IO.StreamReader($context.Request.InputStream, [Text.Encoding]::UTF8)
                $text = $reader.ReadToEnd(); $reader.Dispose()
                if ([string]::IsNullOrWhiteSpace($text)) { Send-Response $context 400 'Empty message'; return }
                $stamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
                Add-Content -Path (Join-Path $Inbox 'Messages.txt') -Value "[$stamp] $text`r`n" -Encoding UTF8
                $window.Dispatcher.Invoke([action]{ [System.Windows.Clipboard]::SetText($text) })
                Add-History "Message: $($text.Substring(0, [Math]::Min(55, $text.Length)))"
                Show-Arrival 'PocketDrop message' 'Copied to clipboard'
                Send-Response $context 200 'OK'
            }
            '/api/file' {
                $encodedName = $context.Request.Headers['X-File-Name']
                $name = if ($encodedName) { [Uri]::UnescapeDataString($encodedName.Replace('+',' ')) } else { 'PocketDrop_file' }
                $name = [IO.Path]::GetFileName($name)
                foreach ($char in [IO.Path]::GetInvalidFileNameChars()) { $name = $name.Replace([string]$char, '_') }
                $target = Join-Path $Inbox $name
                if (Test-Path $target) {
                    $base = [IO.Path]::GetFileNameWithoutExtension($name); $ext = [IO.Path]::GetExtension($name)
                    $target = Join-Path $Inbox "${base}_$(Get-Date -Format 'yyyyMMdd_HHmmss')${ext}"
                }
                $output = [IO.File]::Create($target)
                $context.Request.InputStream.CopyTo($output)
                $output.Dispose()
                Add-History "File: $([IO.Path]::GetFileName($target))"
                Show-Arrival 'PocketDrop received' ([IO.Path]::GetFileName($target))
                Send-Response $context 200 'OK'
            }
            default { Send-Response $context 404 'Unknown PocketDrop endpoint' }
        }
    } catch {
        try { Send-Response $context 500 $_.Exception.Message } catch {}
    }
}

$listener = New-Object Net.HttpListener
$listener.Prefixes.Add("http://+:$Port/")
try { $listener.Start() } catch {
    [System.Windows.MessageBox]::Show("PocketDrop could not start listening.`n`n$($_.Exception.Message)", 'PocketDrop') | Out-Null
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

$window.FindName('CopyButton').add_Click({
    [System.Windows.Clipboard]::SetText("Desktop address: $Address`r`nPrivate key: $Token")
    $window.FindName('StatusText').Text = 'Setup copied'
})
$window.FindName('FolderButton').add_Click({ Start-Process explorer.exe $Inbox })
$startupBox.add_Click({
    if ($startupBox.IsChecked) {
        $shell = New-Object -ComObject WScript.Shell
        $shortcut = $shell.CreateShortcut($StartupShortcut)
        $shortcut.TargetPath = 'powershell.exe'
        $shortcut.Arguments = "-WindowStyle Hidden -NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" -Startup"
        $shortcut.WorkingDirectory = Split-Path $PSCommandPath
        $shortcut.Save()
    } else { Remove-Item $StartupShortcut -Force -ErrorAction SilentlyContinue }
})

$script:AllowClose = $false
$window.add_Closing({ param($sender, $e)
    if (-not $script:AllowClose) { $e.Cancel = $true; $window.Hide() }
})
$window.add_Closed({ $pollTimer.Stop(); $listener.Stop(); $listener.Close(); $notify.Visible = $false; $notify.Dispose() })
if ($Startup) { $window.add_ContentRendered({ $window.Hide() }) }
[void]$window.ShowDialog()
