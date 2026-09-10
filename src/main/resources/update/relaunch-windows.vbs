On Error Resume Next
Set fso = CreateObject("Scripting.FileSystemObject")
Set lg = fso.OpenTextFile("{{log}}", 8, True)
lg.WriteLine Now & " wait {{pid}}"
Set wmi = GetObject("winmgmts:\\.\root\cimv2")
For i = 1 To 120
  If wmi.ExecQuery("SELECT ProcessId FROM Win32_Process WHERE ProcessId={{pid}}").Count = 0 Then Exit For
  WScript.Sleep 1000
Next
Set sh = CreateObject("WScript.Shell")
sh.CurrentDirectory = "{{cwd}}"
sh.Run "{{command}}", 1, False
lg.WriteLine Now & " done " & Err.Number & " " & Err.Description
lg.Close
fso.DeleteFile WScript.ScriptFullName
