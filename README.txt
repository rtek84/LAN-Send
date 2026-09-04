POCKETDROP 3.0
================

WHAT IT DOES
Send screenshots, pictures, files, messages and links in both directions
between Android and a Windows PC over the same Wi-Fi network.

WINDOWS SETUP
1. Open the PC folder.
2. Double-click Start-PocketDrop.cmd.
3. Windows will ask for administrator permission on the first run only. This
   creates PocketDrop's local network permission.
4. If Windows asks about network access, allow Private networks.
5. Keep the receiver running. Closing its window sends it to the system tray.

ANDROID SETUP
1. Open this PocketDrop folder in Android Studio.
2. Let Gradle finish syncing.
3. Connect your POCO phone and press Run.
4. Tap Connect to PC and point the phone at the QR displayed by PocketDrop PC.
   Both devices are registered for two-way transfers automatically.
5. Manual address and private-key entry remain available as a fallback.

SEND A SCREENSHOT
Open a screenshot in Gallery > Share > PocketDrop. If the PC connection
has already been saved, the file begins sending immediately.

WHERE FILES GO
Documents\PocketDrop Inbox

Files sent from PC to phone go to:
Downloads/PocketDrop

SEND FROM PC TO PHONE
- Type a message in PocketDrop PC and click Send message.
- Click Choose file, or drag files anywhere onto the PocketDrop PC window.
- Android displays a notification whenever something arrives.

Messages are appended to Messages.txt and are also copied automatically to the
Windows clipboard.

IMPORTANT
- Phone and PC must currently be on the same home Wi-Fi/LAN.
- The receiver accepts requests only when the private key matches.
- Do not forward TCP port 8734 on your router; PocketDrop is intended for your
  private home network only.
- If the PC's local IP address changes, copy the newly displayed PC address
  into the phone app again.
