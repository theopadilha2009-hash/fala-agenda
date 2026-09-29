# dontkillmyapp — atribuição

As instruções por fabricante que aparecem no item "Não matar alarmes" do aplicativo vêm da
base de dados do projeto [dont-kill-my-app](https://github.com/urbandroid-team/dont-kill-my-app),
mantido pelo urbandroid team e publicado em <https://dontkillmyapp.com>.

- **Obra original:** dontkillmyapp.com — urbandroid-team/dont-kill-my-app
- **Licença:** Creative Commons Attribution 4.0 International (CC-BY-4.0) —
  <https://creativecommons.org/licenses/by/4.0/>
  Texto da licença:
  <https://github.com/urbandroid-team/dont-kill-my-app/blob/master/LICENCE>
- **O que foi usado:** as instruções de usuário (`_vendors-content/<fabricante>/user.md`) dos
  fabricantes Xiaomi, Samsung, Motorola, Huawei, OnePlus, Asus, Nokia, Vivo, Oppo, Realme e
  Sony.
- **Modificações:** tradução para o português, reescrita em frases curtas e corte para no
  máximo cinco passos por tela.
- **Consultado em:** 2026-09-29.

## O que não vem de lá

- **Honor:** o projeto original **não tem página de Honor** (não existe `honor/user.md`). Os
  passos do Honor são adaptados da página da Huawei — por isso levam o mesmo crédito —, e
  isso está dito no comentário do `ManufacturerGuide.kt`.
- **LG e TCL:** também não têm página no projeto original. Recebem o caminho genérico do
  Android, escrito neste app, e a tela diz que não há ajuste extra do fabricante.
- **O texto genérico** (aparelho fora da lista, AOSP ou `Build.MANUFACTURER` vazio) é do
  próprio app, de antes deste trabalho, e **não** leva o crédito do dontkillmyapp.

O crédito aparece na tela só nos guias que saem do projeto original: "Instruções baseadas em
dontkillmyapp.com" (`ManufacturerGuide.CREDIT`, em
`domain/.../reminder/ManufacturerGuide.kt`), e está também no comentário de cabeçalho do
mesmo arquivo.
