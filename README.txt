LAN SEND 3.0
================

WHAT IT DOES
Send screenshots, pictures, files, messages and links in both directions
between Android and a Windows PC over the same Wi-Fi network.

WINDOWS SETUP
1. Run LAN-Send-Setup-3.0.0.exe and follow the installer.
2. The installer creates the Start Menu entry, configures Windows Firewall,
   and can create Desktop and Start-with-Windows shortcuts.
3. LAN Send runs without a PowerShell console window. Closing the main window
   sends it to the notification area; use its tray menu to exit fully.

BUILD THE WINDOWS INSTALLER
1. Install NSIS from https://nsis.sourceforge.io/Download.
2. Open the PC folder and double-click Build-Installer.cmd.
3. The finished installer is written to PC\Installer\Output.

ANDROID SETUP
1. Open this LAN Send folder in Android Studio.
2. Let Gradle finish syncing.
3. Connect your POCO phone and press Run.
4. Tap Connect to PC and point the phone at the QR displayed by LAN Send PC.
   Both devices are registered for two-way transfers automatically.
5. Manual address and private-key entry remain available as a fallback.

SEND A SCREENSHOT
Open a screenshot in Gallery > Share > LAN Send. If the PC connection
has already been saved, the file begins sending immediately.

WHERE FILES GO
Documents\LAN Send Inbox

Files sent from PC to phone go to:
Downloads/LAN Send

SEND FROM PC TO PHONE
- Type a message in LAN Send PC and click Send message.
- Click Choose file, or drag files anywhere onto the LAN Send PC window.
- Android displays a notification whenever something arrives.

Messages are appended to Messages.txt and are also copied automatically to the
Windows clipboard.

IMPORTANT
- Phone and PC must currently be on the same home Wi-Fi/LAN.
- The receiver accepts requests only when the private key matches.
- Do not forward TCP port 8734 on your router; LAN Send is intended for your
  private home network only.
- If the PC's local IP address changes, copy the newly displayed PC address
  into the phone app again.
