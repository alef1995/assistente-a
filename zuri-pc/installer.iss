; Instalador da Zuri para Windows (Inno Setup). Instala para o usuário atual, sem pedir administrador.
#define MyAppName "Zuri"
#define MyAppVersion GetEnv("ZURI_VERSION")

[Setup]
AppId={{6B2F0C3E-9A41-4C5B-8E0B-2E7D7A6F1A01}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher=alef1995
DefaultDirName={localappdata}\Programs\Zuri
DefaultGroupName=Zuri
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=Output
OutputBaseFilename=ZuriSetup
SetupIconFile=zuri.ico
UninstallDisplayIcon={app}\Zuri.exe
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes

[Languages]
Name: "pt"; MessagesFile: "compiler:Languages\BrazilianPortuguese.isl"

[Tasks]
Name: "desktopicon"; Description: "Criar atalho na área de trabalho"; GroupDescription: "Atalhos:"

[Files]
Source: "dist\Zuri\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\Zuri"; Filename: "{app}\Zuri.exe"
Name: "{group}\Desinstalar a Zuri"; Filename: "{uninstallexe}"
Name: "{userdesktop}\Zuri"; Filename: "{app}\Zuri.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\Zuri.exe"; Description: "Abrir a Zuri agora"; Flags: nowait postinstall skipifsilent
