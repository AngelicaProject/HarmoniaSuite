On Error Resume Next
Set fso = CreateObject("Scripting.FileSystemObject")
Set lg = fso.OpenTextFile("{{log}}", 8, True)
lg.WriteLine Now & " wait {{pid}}"
Set wmi = GetObject("winmgmts:\\.\root\cimv2")
For i = 1 To 120
  If wmi.ExecQuery("SELECT ProcessId FROM Win32_Process WHERE ProcessId={{pid}}").Count = 0 Then Exit For
  WScript.Sleep 1000
Next
lg.WriteLine Now & " copy"
fso.CopyFile "{{built}}", "{{appJar}}", True
For i = 1 To 10
  If Err.Number = 0 Then Exit For
  Err.Clear
  WScript.Sleep 1000
  fso.CopyFile "{{built}}", "{{appJar}}", True
Next
If Err.Number = 0 Then
  lg.WriteLine Now & " start"
  Set sh = CreateObject("WScript.Shell")
  sh.Environment("PROCESS")("HARMONIA_NO_BROWSER") = "1"
  sh.Run "{{exe}}", 1, False
Else
  lg.WriteLine Now & " copy failed"
End If
lg.WriteLine Now & " done " & Err.Number & " " & Err.Description
lg.Close
fso.DeleteFile WScript.ScriptFullName
