# Launcher TV TCL (uso pessoal)

Versão adaptada da launcher https://github.com/wiiguy/lightweight_androidtv_launcher.

## Gerar o APK
1. No GitHub, abra **Actions > Build APK > Run workflow**.
2. Quando terminar, baixe o artefato **tv-launcher-apk** (zip com `app-release.apk`).
3. Cada build usa uma chave nova, então desinstale a versão anterior antes de instalar a nova.
   Para atualizar sem desinstalar, crie os segredos `KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`,
   `SIGNING_KEY_ALIAS` e `SIGNING_KEY_PASSWORD` no repositório.

## Botão Home no lugar da Google TV
O app usa um serviço de acessibilidade ("TV Launcher") para capturar o botão Home.
As opções já vêm ligadas. Se a TV desligar o serviço sozinha, libere uma vez, por ADB:

    adb shell pm grant com.tvlauncher android.permission.WRITE_SECURE_SETTINGS

Depois disso a launcher liga o serviço sozinha sempre que for aberta.
O último erro do app pode ser visto em **Configurações da launcher > Ver último erro do app**.

## Tela inicial
- Abas no topo: Início, suas categorias, "+ Categoria" e "Todos os apps".
- **Todos os apps**: OK em um app não adicionado pergunta a categoria; segurar OK (ou OK em um app
  já adicionado) abre mover, remover do início, abrir e desinstalar.
- Em uma aba de categoria, OK na própria aba abre renomear, mover e excluir.
- Configurações da launcher: tamanho dos ícones (8 níveis), tamanho e texto da frase, papel de
  parede (pasta, online ou link, com tempo de troca), acessibilidade e último erro.

## Novidades (v3)

- **Chave de assinatura fixa**: o workflow cria uma chave sua na primeira compilação e a guarda no cache do GitHub Actions, então os próximos APKs atualizam por cima sem perder dados. O cache some após 7 dias sem compilar; para uma chave permanente, cadastre os secrets `KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`. (A chave do projeto original é segredo do autor e não pode ser usada.)
- **Backup**: Personalizar → "Salvar configurações em arquivo" / "Restaurar configurações de arquivo" (precisa de um seletor de arquivos na TV).
- **Aba Entradas**: atalhos para TV ao vivo e HDMI (pode não funcionar em todas as TVs TCL).
- **Saudação por horário** (Bom dia/Boa tarde/Boa noite) e frases que se alternam a cada 10 min: a 1ª linha do texto é o título, cada linha seguinte é uma frase.
- **Mais usados**: fileira no topo da aba inicial (liga/desliga em Personalizar).
- **Liberar memória agora**: em Personalizar.
- **PIN por categoria**: OK na aba da categoria → "Proteger com PIN". Só esconde o conteúdo da aba; os apps continuam aparecendo em "Todos os apps".
