' Runs publish.bat with no window (used by the "Loudbook publish" scheduled task).
Set fso = CreateObject("Scripting.FileSystemObject")
here = fso.GetParentFolderName(WScript.ScriptFullName)
CreateObject("WScript.Shell").Run "cmd /c """ & here & "\publish.bat""", 0, True
