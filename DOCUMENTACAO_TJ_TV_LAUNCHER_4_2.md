# TJ TV Launcher — documentação técnica da versão 4.2

> Documento de arquitetura, código, recursos e scripts usados para elaborar, testar e publicar o app.
> Não contém tokens, chaves de API, senhas nem dados pessoais do aparelho.

## 1. Identidade e build

- Nome: **TJ TV Launcher**
- Pacote: `com.tvlauncher`
- Namespace: `com.tvlauncher`
- Versão de referência desta rodada: **4.2**
- `minSdk 21`, `targetSdk 34`, `compileSdk 34`
- Kotlin + Android Views (`RecyclerView`, `ConstraintLayout`, `AppCompat`)
- Flavor F-Droid **removido**: o app é publicado como launcher independente, com o
  atualizador interno ligado (`BuildConfig.UPDATES_ENABLED = true`).

### Tarefas Gradle

```bash
./gradlew testDebugUnitTest --no-daemon
./gradlew assembleRelease --no-daemon
```

APK gerado:

```text
app/build/outputs/apk/release/app-release.apk
```

## 2. Estrutura do projeto

```text
 Projeto/
├── app/
│   ├── build.gradle
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/tvlauncher/
│       │   │   ├── MainActivity.kt
│       │   │   ├── TvLauncherApplication.kt
│       │   │   ├── AppUpdateManager.kt
│       │   │   ├── UpdatePromptActivity.kt
│       │   │   ├── UpdateWorker.kt
│       │   │   ├── UpdaterBridge.kt
│       │   │   ├── UpdateResult.kt
│       │   │   ├── TvHomeOverrideService.kt
│       │   │   ├── RecentsActivity.kt
│       │   │   ├── AppSelectionActivity.kt
│       │   │   ├── AppManager.kt
│       │   │   ├── AppInfo.kt
│       │   │   ├── AppIdentifier.kt
│       │   │   ├── AppSlotAdapter.kt
│       │   │   ├── AllAppsAdapter.kt
│       │   │   ├── SearchAdapter.kt
│       │   │   ├── LayoutPrefs.kt
│       │   │   ├── HomeLayout.kt
│       │   │   ├── HiddenApps.kt
│       │   │   ├── UsageTracker.kt
│       │   │   ├── Palette.kt
│       │   │   ├── IconShapes.kt
│       │   │   ├── ScreenEffect.kt
│       │   │   ├── AuroraView.kt
│       │   │   ├── RandomMessages.kt
│       │   │   ├── GreetingText.kt
│       │   │   ├── Weather.kt
│       │   │   ├── WeatherForecastDialog.kt
│       │   │   ├── SystemStats.kt
│       │   │   ├── NetworkUsageTracker.kt
│       │   │   ├── CacheCleaner.kt
│       │   │   ├── WallpaperManager.kt
│       │   │   ├── ContentBannerManager.kt
│       │   │   ├── ContentBannerAdapter.kt
│       │   │   ├── ProfilePhotoAdjustDialog.kt
│       │   │   ├── SettingsBackup.kt
│       │   │   ├── CrashLog.kt
│       │   │   ├── ShortcutHelper.kt
│       │   │   ├── RamOptimizationHelper.kt
│       │   │   ├── BootReceiver.kt
│       │   │   └── AccessibilityEnabler.kt
│       │   └── res/
│       │       ├── drawable/       # ícones vetoriais e fundos
│       │       ├── layout/         # telas, diálogos, linhas e banners
│       │       ├── mipmap-*/       # ícone da launcher
│       │       ├── values/         # cores, dimensões, estilos e textos
│       │       ├── values-port/    # margens de retrato
│       │       └── xml/            # backup, rede e acessibilidade
│       └── test/java/com/tvlauncher/ # testes JVM das regras puras
├── gradle/wrapper/
├── .github/workflows/
│   ├── build-apk.yml
│   ├── ci.yml
│   └── release.yml
├── LEIA-ME_TV_TCL.md
└── DOCUMENTACAO_TJ_TV_LAUNCHER_4_2.md
```

## 3. Estado e persistência

| Preferências/arquivo | Conteúdo |
|---|---|
| `launcher_layout` | abas, grade, tamanho global dos ícones, efeito de tela, formas, paleta, frases e intervalo |
| `launcher_settings` | seleção/ordem de apps, Home e otimização |
| `accessibility_enabler` | controle do lembrete do serviço de acessibilidade (ponto de tempo e versão) |
| `launcher_hidden_apps` | IDs dos apps ocultos pelo usuário |
| `usage_counts` | contador de apps mais usados |
| `launcher_banners` | origem, visibilidade e player dos banners |
| `launcher_secrets` | chaves de API; nunca entram no backup |
| `weather_cache` | resposta e horário da última atualização do clima |
| `network_usage` | referências para consumo diário e mensal |
| `filesDir/profile_photo.jpg` | avatar ajustado e recortado em círculo |
| `filesDir/events.log` | eventos para suporte |
| `filesDir/last_crash.txt` | último erro fatal |

IDs de app continuam usando `AppIdentifier.encode/decode`, inclusive para apps ocultos e atalhos.

## 4. Regras principais da 4.2

### 4.1 Apps, categorias e apps ocultos

- Todo app instalado aparece inicialmente em **Todos os apps**.
- Um app associado ao Início ou a uma categoria deixa de aparecer em **Todos os apps**.
- Ao remover o atalho ou excluir a categoria, ele volta automaticamente para **Todos os apps**.
- Segurar um app por mais de 700 ms abre o menu moderno, incluindo **Ocultar app**.
- **Configurações avançadas → Apps ocultos** permite reexibir individualmente ou todos.

Exemplo de regra:

```kotlin
val visibleInAllApps = installedApps.filter { app ->
    val id = AppIdentifier.encode(app)
    id !in hiddenIds && id !in assignedOrHomeIds
}
```

### 4.2 Ícones, grade e efeito de tela

- O drawable original não é mais convertido em bitmap quadrado.
- `ImageView.ScaleType.FIT_CENTER` preserva proporção e evita esticamento.
- **Padrão (cartão moderno)**: cartão horizontal arredondado inspirado na referência enviada.
- Outras formas: arredondado, redondo, retângulo e super arredondado.
- Largura do slot segue as colunas; altura segue as linhas.
- Na grade, colunas e linhas aumentam ao arrastar para a direita.
- Na barra de tamanho, arrastar para a direita aumenta os ícones.
- **Tamanho dos ícones é global** (`icon_scale_percent`, 60% a 130%, padrão 100%): vale para
  todas as abas, a opção não existe mais por aba e nunca muda a quantidade de itens da grade.
- **Efeito de tela** (`ScreenEffect.kt` + `AuroraView.kt`), desenhado em canvas puro atrás do
  conteúdo, sempre com as cores da paleta escolhida: Nenhum, Aurora, Vinheta escura,
  Vinheta colorida, Neblina, Estrelas, Ondas, Moldura neon, Grade retrô e Bolinhas (bokeh).
  A configuração antiga "aurora on/off" é convertida automaticamente na nova opção.

### 4.3 Launcher padrão e botão Home

- A launcher se torna a **Home padrão** do sistema: em TVs, o fluxo abre direto a tela
  **Início/Home** das configurações do Android (`ACTION_HOME_SETTINGS`), não o diálogo do
  `RoleManager`, que em TVs normalmente não existe ou não tem efeito; não há outras telas,
  o fluxo cai em `Apps padrão` → `Configurações` e por fim mostra o comando ADB que resolve
  mesmo em TVs bloqueadas (comum na TCL):
  `adb shell cmd package set-home-activity com.tvlauncher/.MainActivity`.
- Ainda não sendo o padrão, o aviso aparece na primeira execução e depois no máximo a cada
  7 dias; ao voltar das configurações a launcher confere e comunica o resultado.
- O **botão Home** volta a abrir a launcher pelo serviço de acessibilidade
  (`TvHomeOverrideService`), que também relança a launcher quando a original aparece na tela.
- Se o `WRITE_SECURE_SETTINGS` foi liberado uma vez por ADB
  (`adb shell pm grant com.tvlauncher android.permission.WRITE_SECURE_SETTINGS`), a launcher
  **religa sozinha** o serviço de acessibilidade: ao abrir, ao voltar do segundo plano e
  após reiniciar a TV (`BootReceiver`), sempre conferindo o valor salvo no sistema.
- Sem a permissão, ela pede a ativação na primeira execução, após cada atualização (o
  Android desliga o serviço quando o app é atualizado) e depois a cada 3 dias no máximo.
- O mesmo aviso ADB, com os dois comandos, continua acessível em **Configurações avançadas**.

### 4.4 Frases e aparência

- Saudação/editar frase removidos da interface.
- Exibição apenas das **frases automáticas** quando ativadas.
- Intervalos configuráveis e temas: mensagens, poemas, piadas, motivação, cinema e relacionamento.
- Paletas: Índigo, Âmbar, Esmeralda, Rosa, Gelo, Violeta, Turquesa, Coral, Dourado, Azul noite e Lima.
- Menus e diálogos escuros, cantos arredondados, largura proporcional e foco legível.

### 4.5 Clima

- Local: Belém–PA, Terra Firme.
- Open-Meteo, sem chave.
- Atualização automática somente quando o cache tem 12 horas ou mais: no máximo duas vezes/dia.
- Atualização manual permitida pelo botão **Atualizar**.
- Semana em cartões com ícone, data, condição, mínima, máxima e chuva.

### 4.6 Busca

- Ao abrir: somente o campo de pesquisa.
- Resultados locais somente após digitar letras contidas no nome do app.
- Ações externas antes dos apps locais: Internet, YouTube e Play Store.

### 4.7 Avatar e sistema

- Antes de salvar: ajuste de zoom e posição, com prévia circular.
- RAM, Desempenho e armazenamento com valores usados/livres e porcentagens.
- Desempenho mostra capacidade total estimada em GHz, uso e disponibilidade.
- Até sete apps do usuário com maior consumo, porcentagem e botão de encerrar.
- Rede mostra tipo, nome, IP, consumo do dia e totais recebidos/enviados no mês.

### 4.8 Cache seguro

- A launcher limpa seu próprio cache diretamente.
- Para outros apps, utiliza apenas a API de cache do sistema quando disponível.
- Nunca chama `clearApplicationUserData`, desinstala apps nem apaga preferências, contas, fotos ou dados.
- Se o Android bloquear a limpeza de terceiros, a UI informa a limitação sem relatar sucesso falso.

### 4.9 Banners

- YouTube Data API v3: `videos?chart=mostPopular&regionCode=BR`.
- Filtra conteúdo com restrição de idade e termos impróprios.
- TMDb opcional para filmes, sempre com `include_adult=false` e classificação adequada.
- Chaves ficam em `launcher_secrets` e fora do backup.
- O usuário escolhe o app usado para abrir vídeos/filmes.
- Imagens somente por HTTPS, com limite de download e cache temporário.

## 5. Scripts de CI e entrega

Workflow `.github/workflows/build-apk.yml`:

```yaml
- name: Run unit tests
  run: ./gradlew testDebugUnitTest --no-daemon
- name: Build release APK
  run: ./gradlew assembleRelease --no-daemon
- name: Rename APK
  run: cp app/build/outputs/apk/release/app-release.apk app-release.apk
- name: Upload APK
  uses: actions/upload-artifact@v4
  with:
    name: tv-launcher-apk
    path: app-release.apk
```

Publicação sem Git local, pela API Git Data do GitHub:

1. Ler `refs/heads/main` e a árvore atual.
2. Criar blobs somente dos arquivos alterados.
3. Criar árvore com `base_tree`.
4. Criar commit com a árvore anterior como pai.
5. Atualizar a referência sem `force`.
6. Disparar `build-apk.yml` com `workflow_dispatch`.
7. Baixar `tv-launcher-apk`, extrair e verificar ZIP, `classes.dex`, manifesto e assinatura.

## 6. Segurança

- `FLAG_SECURE` nas telas sensíveis.
- Serviço de acessibilidade sem leitura de texto/conteúdo das janelas.
- Captura de Home limitada a launchers conhecidas e ao comportamento configurado.
- `WRITE_SECURE_SETTINGS` é usada apenas para religar o próprio serviço de acessibilidade
  (permissão concedida voluntariamente por ADB); o `BootReceiver` reativa o serviço depois
  de reiniciar a TV, sem enviar nem executar nada além disso.
- Cleartext desativado; rede configurada por XML.
- Backup exclui segredos, banners, caches e logs.
- Atualizador valida HTTPS, pacote e assinatura.
- Nenhum comando de limpeza apaga dados do usuário.

## 7. Verificação antes da entrega

```text
[ ] Compilação Kotlin/Java sem erros
[ ] Testes JVM aprovados
[ ] Recursos R.string/R.id/R.drawable presentes
[ ] XMLs bem formados
[ ] APK assinado e íntegro
[ ] Versão 4.2 (versionCode 40200) no build.gradle
[ ] APK renomeado para TJ-TV-Launcher-4.2.apk
[ ] Teste na TV: ícones, grade, menus, apps ocultos, busca, clima, avatar e banners
```
