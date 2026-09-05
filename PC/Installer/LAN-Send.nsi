Unicode True

!include "MUI2.nsh"
!include "LogicLib.nsh"

!define APP_NAME "LAN Send"
!define APP_VERSION "3.0.0"
!define APP_PUBLISHER "LAN Send"
!define APP_DIR "LAN Send"
!define APP_UNINSTALL_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\LAN Send"

Name "${APP_NAME} ${APP_VERSION}"
OutFile "Output\LAN-Send-Setup-${APP_VERSION}.exe"
InstallDir "$PROGRAMFILES64\${APP_DIR}"
InstallDirRegKey HKLM "${APP_UNINSTALL_KEY}" "InstallLocation"
RequestExecutionLevel admin
SetCompressor /SOLID lzma
Icon "..\LAN-Send.ico"
UninstallIcon "..\LAN-Send.ico"
BrandingText "LAN Send"
ShowInstDetails nevershow
ShowUninstDetails nevershow

VIProductVersion "3.0.0.0"
VIAddVersionKey "ProductName" "${APP_NAME}"
VIAddVersionKey "FileDescription" "LAN Send installer"
VIAddVersionKey "FileVersion" "${APP_VERSION}"
VIAddVersionKey "ProductVersion" "${APP_VERSION}"
VIAddVersionKey "CompanyName" "${APP_PUBLISHER}"
VIAddVersionKey "LegalCopyright" "Copyright © 2026 LAN Send"

!define MUI_ABORTWARNING
!define MUI_ICON "..\LAN-Send.ico"
!define MUI_UNICON "..\LAN-Send.ico"
!define MUI_FINISHPAGE_RUN "$SYSDIR\wscript.exe"
!define MUI_FINISHPAGE_RUN_PARAMETERS '$\"$INSTDIR\LANSend-Launcher.vbs$\"'
!define MUI_FINISHPAGE_RUN_TEXT "Launch LAN Send"

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_UNPAGE_FINISH

!insertmacro MUI_LANGUAGE "English"

Section "LAN Send PC (required)" SecCore
  SectionIn RO
  SetShellVarContext current
  SetOutPath "$INSTDIR"

  File "..\LANSend-Receiver.ps1"
  File "..\LANSend-Launcher.vbs"
  File "..\LAN-Send.ico"
  File "..\LAN-Send-Logo.png"

  WriteUninstaller "$INSTDIR\Uninstall LAN Send.exe"

  CreateDirectory "$SMPROGRAMS\LAN Send"
  CreateShortCut "$SMPROGRAMS\LAN Send\LAN Send.lnk" "$SYSDIR\wscript.exe" '$\"$INSTDIR\LANSend-Launcher.vbs$\"' "$INSTDIR\LAN-Send.ico" 0 SW_SHOWNORMAL "" "Open LAN Send"
  CreateShortCut "$SMPROGRAMS\LAN Send\Uninstall LAN Send.lnk" "$INSTDIR\Uninstall LAN Send.exe" "" "$INSTDIR\LAN-Send.ico"

  WriteRegStr HKLM "${APP_UNINSTALL_KEY}" "DisplayName" "${APP_NAME}"
  WriteRegStr HKLM "${APP_UNINSTALL_KEY}" "DisplayVersion" "${APP_VERSION}"
  WriteRegStr HKLM "${APP_UNINSTALL_KEY}" "Publisher" "${APP_PUBLISHER}"
  WriteRegStr HKLM "${APP_UNINSTALL_KEY}" "DisplayIcon" "$INSTDIR\LAN-Send.ico"
  WriteRegStr HKLM "${APP_UNINSTALL_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKLM "${APP_UNINSTALL_KEY}" "UninstallString" '$\"$INSTDIR\Uninstall LAN Send.exe$\"'
  WriteRegDWORD HKLM "${APP_UNINSTALL_KEY}" "NoModify" 1
  WriteRegDWORD HKLM "${APP_UNINSTALL_KEY}" "NoRepair" 1

  DetailPrint "Configuring local network access..."
  nsExec::ExecToLog '$\"$SYSDIR\netsh.exe$\" http delete urlacl url=http://+:8734/'
  Pop $0
  nsExec::ExecToLog '$\"$SYSDIR\netsh.exe$\" http add urlacl url=http://+:8734/ sddl=D:(A;;GX;;;BU)'
  Pop $0

  DetailPrint "Configuring Windows Firewall..."
  nsExec::ExecToLog '$\"$SYSDIR\netsh.exe$\" advfirewall firewall delete rule name=$\"PocketDrop Receiver$\"'
  Pop $0
  nsExec::ExecToLog '$\"$SYSDIR\netsh.exe$\" advfirewall firewall delete rule name=$\"LAN Send Receiver$\"'
  Pop $0
  nsExec::ExecToLog '$\"$SYSDIR\netsh.exe$\" advfirewall firewall add rule name=$\"LAN Send Receiver$\" dir=in action=allow protocol=TCP localport=8734 profile=private'
  Pop $0
SectionEnd

Section "Desktop shortcut" SecDesktop
  SetShellVarContext current
  CreateShortCut "$DESKTOP\LAN Send.lnk" "$SYSDIR\wscript.exe" '$\"$INSTDIR\LANSend-Launcher.vbs$\"' "$INSTDIR\LAN-Send.ico" 0 SW_SHOWNORMAL "" "Open LAN Send"
SectionEnd

Section "Start automatically with Windows" SecStartup
  SetShellVarContext current
  CreateShortCut "$SMSTARTUP\LAN Send Receiver.lnk" "$SYSDIR\wscript.exe" '$\"$INSTDIR\LANSend-Launcher.vbs$\" Startup' "$INSTDIR\LAN-Send.ico" 0 SW_SHOWNORMAL "" "Start LAN Send in the notification area"
SectionEnd

LangString DESC_SecCore ${LANG_ENGLISH} "Installs LAN Send and configures private-network access."
LangString DESC_SecDesktop ${LANG_ENGLISH} "Creates a LAN Send shortcut on the desktop."
LangString DESC_SecStartup ${LANG_ENGLISH} "Starts LAN Send quietly in the notification area when you sign in."

!insertmacro MUI_FUNCTION_DESCRIPTION_BEGIN
  !insertmacro MUI_DESCRIPTION_TEXT ${SecCore} $(DESC_SecCore)
  !insertmacro MUI_DESCRIPTION_TEXT ${SecDesktop} $(DESC_SecDesktop)
  !insertmacro MUI_DESCRIPTION_TEXT ${SecStartup} $(DESC_SecStartup)
!insertmacro MUI_FUNCTION_DESCRIPTION_END

Section "Uninstall"
  SetShellVarContext current

  DetailPrint "Removing LAN Send network access..."
  nsExec::ExecToLog '$\"$SYSDIR\netsh.exe$\" advfirewall firewall delete rule name=$\"LAN Send Receiver$\"'
  Pop $0
  nsExec::ExecToLog '$\"$SYSDIR\netsh.exe$\" http delete urlacl url=http://+:8734/'
  Pop $0

  Delete "$DESKTOP\LAN Send.lnk"
  Delete "$SMSTARTUP\LAN Send Receiver.lnk"
  Delete "$SMSTARTUP\PocketDrop Receiver.lnk"
  Delete "$SMPROGRAMS\LAN Send\LAN Send.lnk"
  Delete "$SMPROGRAMS\LAN Send\Uninstall LAN Send.lnk"
  RMDir "$SMPROGRAMS\LAN Send"

  Delete "$INSTDIR\LANSend-Receiver.ps1"
  Delete "$INSTDIR\LANSend-Launcher.vbs"
  Delete "$INSTDIR\LAN-Send.ico"
  Delete "$INSTDIR\LAN-Send-Logo.png"
  Delete "$INSTDIR\Uninstall LAN Send.exe"
  RMDir "$INSTDIR"

  DeleteRegKey HKLM "${APP_UNINSTALL_KEY}"
SectionEnd
