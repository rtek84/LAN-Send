POCKETDROP 2.0
================

WHAT IT DOES
Send screenshots, pictures, files, messages and links from Android directly to
your Windows PC over the same Wi-Fi network.

WINDOWS SETUP
1. Open the Desktop folder.
2. Double-click Start-PocketDrop.cmd.
3. Windows will ask for administrator permission on the first run only. This
   creates PocketDrop's local network permission.
4. If Windows asks about network access, allow Private networks.
5. Keep the receiver running. Closing its window sends it to the system tray.

ANDROID SETUP
1. Open this PocketDrop folder in Android Studio.
2. Let Gradle finish syncing.
3. Connect your POCO phone and press Run.
4. Tap Scan desktop QR code and point the phone at the QR displayed by the
   Windows receiver. The address and private key are saved automatically.
5. Manual address and private-key entry remain available as a fallback.

SEND A SCREENSHOT
Open a screenshot in Gallery > Share > PocketDrop. If the desktop connection
has already been saved, the file begins sending immediately.

WHERE FILES GO
Documents\PocketDrop Inbox

Messages are appended to Messages.txt and are also copied automatically to the
Windows clipboard.

IMPORTANT
- Phone and PC must currently be on the same home Wi-Fi/LAN.
- The receiver accepts requests only when the private key matches.
- Do not forward TCP port 8734 on your router; PocketDrop is intended for your
  private home network only.
- If the PC's local IP address changes, copy the newly displayed Desktop address
  into the phone app again.
