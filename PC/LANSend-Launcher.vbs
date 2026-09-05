Option Explicit

Dim shell, fileSystem, scriptFolder, receiverPath, arguments
Set shell = CreateObject("WScript.Shell")
Set fileSystem = CreateObject("Scripting.FileSystemObject")

scriptFolder = fileSystem.GetParentFolderName(WScript.ScriptFullName)
receiverPath = fileSystem.BuildPath(scriptFolder, "LANSend-Receiver.ps1")
arguments = "-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File """ & receiverPath & """"

If WScript.Arguments.Count > 0 Then
    If LCase(WScript.Arguments(0)) = "startup" Then arguments = arguments & " -Startup"
End If

shell.CurrentDirectory = scriptFolder
shell.Run "powershell.exe " & arguments, 0, False
