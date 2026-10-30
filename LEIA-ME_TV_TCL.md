# TJ TV Launcher 4.0

Launcher pessoal para Android TV/TCL, adaptada do projeto
https://github.com/wiiguy/lightweight_androidtv_launcher.

## Gerar o APK
1. No GitHub, abra **Actions > Build APK > Run workflow**.
2. Quando terminar, baixe o artefato **tv-launcher-apk** (ZIP com `app-release.apk`).
3. O workflow guarda a chave de assinatura no cache do GitHub Actions para que as próximas versões
   possam atualizar por cima sem perder dados. Esse cache expira após 7 dias sem compilação; se a
   assinatura mudar, desinstale a versão anterior antes de instalar o novo APK.
4. Para manter uma chave permanente, cadastre os segredos `KEYSTORE_BASE64`,
   `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` e `SIGNING_KEY_PASSWORD` no repositório.

## Tela inicial e navegação
- Abas **Início**, categorias pessoais e **Todos os apps**, sem aba “Entradas” e sem aba
  “+ Categoria”.
- Tiles no estilo Google TV: ícone grande, sem caixa nem zoom, foco com brilho suave e nome do app
  somente quando o tile está focado.
- **Buscar apps**: botão de lupa no topo, com sugestões antes de digitar e busca que ignora
  maiúsculas/minúsculas e acentos.
- **Todos os apps**: lista compacta com tamanho próprio dos ícones. OK abre ou adiciona o app;
  segurar OK abre as ações disponíveis.
- Menu do app: abrir, reordenar, mover, informações, desinstalar, **Configurações do Android do
  app** e atalho direto para **Permissões**.
- OK em uma aba de categoria abre renomear, excluir, proteger com PIN, alterar o tamanho dos
  ícones e mover a categoria para a esquerda ou direita.

## Personalização
- **Tamanho dos ícones por aba**: cada categoria e “Todos os apps” podem ter tamanho diferente.
  A escolha é feita arrastando uma barra: para a direita, ícones maiores; para a esquerda, menores.
- **Arranjo dos ícones**: quantidade de linhas e colunas, de 1×2 até 15×15. O arranjo global vale
  para todas as abas e os tamanhos dos ícones e textos acompanham a grade.
- Layout calculado pela largura e altura reais da tela, com margens menores, suporte a 4K UHD
  (3840×2160), rotação e orientação retrato.
- Formatos de ícone: padrão, arredondado, redondo, retângulo ou super arredondado.
- Paleta de cores, aurora suave, frases automáticas e saudação por horário.
- Clima de Belém - PA (Terra Firme), com atualização periódica e previsão da semana.
- Papel de parede configurado somente em **Configurações da launcher > Papel de parede**: imagens
  online, link HTTPS ou pasta local com troca automática.
- Se a TV não tiver seletor de pastas, a launcher abre os apps capazes de escolher imagens e salva
  uma cópia particular das fotos selecionadas para continuar alternando o fundo.

## Avatar e painel do sistema
- **Segurar OK no avatar** no canto superior esquerdo permite escolher uma foto.
- **OK no avatar** abre o painel com RAM, processador, até quatro apps com indicação colorida de
  consumo, conexão Wi-Fi/cabo, nome da rede, IP, tráfego e armazenamento total/usado/livre.

## Botão Home e apps abertos
- O serviço de acessibilidade captura o botão Home sem mostrar um pedido de ativação ao iniciar.
- Um toque curto abre a TJ TV Launcher. **Segurar Home por mais de 700 ms** abre **Apps abertos**,
  onde é possível encerrar um app ou todos; apps do sistema e launchers não aparecem.
- Em **Configurações avançadas > Acessibilidade e botão Home** há status, ativação e ajuda para TVs
  que desligam o serviço automaticamente.
- Se necessário, libere uma vez por ADB:

      adb shell pm grant com.tvlauncher android.permission.WRITE_SECURE_SETTINGS

- **Forçar esta launcher como padrão do sistema** abre a escolha do Android e informa se a troca
  foi concluída.

## Configurações avançadas
Centralizam:
- acessibilidade/botão Home e launcher padrão;
- liberação de memória com confirmação;
- exportar e restaurar backup (sem chave do YouTube nem escolha de player);
- log de eventos, com dados do aparelho/configuração, último erro, eventos e exportação em TXT;
- limpeza somente dos caches temporários da launcher, preservando configurações, fotos e dados dos
  outros apps;
- atualizações, quando disponíveis no flavor de compilação;
- controle da launcher original da TV e troca do PIN, quando existente.

## Banners e segurança
- Banners **Em alta** do YouTube usam a API oficial Data v3; a chave fica somente no aparelho.
- O player preferido do YouTube pode ser escolhido e trocado. Youcine permanece apenas como
  atalho para o app, sem catálogo próprio.
- PIN com PBKDF2 e bloqueio progressivo, conexões de imagem somente por HTTPS, limites de tamanho,
  proteção contra captura de tela nas janelas sensíveis e validação de pacote/assinatura no updater.
- O banner e a tela **Sobre** mostram a nova identidade **TJ TV Launcher — Versão 4.0**, sem links,
  fotos ou informações antigas da versão anterior.
