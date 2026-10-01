' Starts the Loudbook PC voice with no window (used at sign-in and by the setup).
Set fso = CreateObject("Scripting.FileSystemObject")
here = fso.GetParentFolderName(WScript.ScriptFullName)
CreateObject("WScript.Shell").Run "cmd /c """ & here & "\start-server.cmd""", 0, False
