' Starts the Loudbook PC voice server with no window (used at log-on and by the setup).
Set fso = CreateObject("Scripting.FileSystemObject")
Set sh = CreateObject("WScript.Shell")
here = fso.GetParentFolderName(WScript.ScriptFullName)
home = sh.ExpandEnvironmentStrings("%LOCALAPPDATA%") & "\Loudbook"
sh.Environment("Process")("UV_PROJECT_ENVIRONMENT") = home & "\venv"
cmd = "cmd /c """"" & home & "\bin\uv.exe"" run --quiet --python 3.12 --project """ & here & """ python """ & here & "\loudbook_server.py"" serve >> """ & home & "\server.log"" 2>&1"""
sh.Run cmd, 0, False
