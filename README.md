# LeafScan

Pesquisa em redes neurais sobre **reconhecimento de doenças em folhas de plantas** e um **app Android offline**
que leva os modelos para o celular.

A pergunta central: modelos que acertam ~99% no **PlantVillage** (fotos de laboratório, fundo liso)
continuam funcionando em **fotos de campo** (PlantDoc)? O projeto treina três CNNs leves do zero, mede a queda
de desempenho entre os dois domínios, testa técnicas para reduzir essa queda e exporta os modelos finais para
TFLite, que rodam no app.

```
leafscan/
├── notebooks/        pipeline de pesquisa (Google Colab), executar na ordem 01 → 05
│   ├── 01_dados.ipynb
│   ├── 02_treino.ipynb
│   ├── 03_avaliacao.ipynb
│   ├── 04_mitigacao.ipynb
│   └── 05_exportar.ipynb
├── models/           .tflite + labels.txt gerados pelo notebook 05 (lidos pelo app)
└── app/              projeto Android Studio (Java + LiteRT)
```

---

## 1. Bases de dados

| Base | Origem | Uso |
|---|---|---|
| **PlantVillage** (colorida) | `spMohanty/PlantVillage-Dataset` | 38 classes, ~54 mil imagens de laboratório. Split estratificado 70/15/15 (treino/validação/teste). |
| **PlantDoc** | `pratikkayal/PlantDoc-Dataset` | Fotos de campo. 27 das 28 classes foram mapeadas para o índice do PlantVillage (`Tomato two spotted spider mites leaf` ficou de fora: só 2 imagens). Split oficial: 2.342 treino / 236 teste. |

Todas as imagens passam pelo mesmo pré-processamento, idêntico ao do app:
**correção de rotação EXIF → RGB → recorte quadrado central → redimensionamento bilinear para 128×128.**

## 2. Modelos

Os três são treinados **sem pesos pré-treinados** e recebem pixels RGB em 0..255 (a normalização fica dentro
do modelo, então o app só envia a imagem).

| Run | Modelo | Parâmetros |
|---|---|---|
| `cnn_base` | CNN própria: 4 blocos (2× Conv-BN-ReLU + MaxPool), filtros 32→256, GAP, dropout 0,3 | 1,18 M |
| `mnv3s` | MobileNetV3-Small | 0,96 M |
| `mnv2_050` | MobileNetV2 α=0,5 | 0,75 M |

Treino: AdamW (lr 2e-3, weight decay 1e-4) com warmup + cosine decay, batch 128, até 20 épocas com early
stopping (paciência 6), precisão mista float16, seed 42. Nas MobileNets o momentum do BatchNorm é baixado de
0,999 para 0,99, porque com 0,999 a validação travava no treino do zero.

## 3. Notebooks (pipeline de pesquisa)

Todos rodam no **Google Colab** e guardam dados e resultados em `MyDrive/leafscan/` no Google Drive.
Cada notebook lê o que o anterior salvou, então a ordem importa.

| # | Notebook | O que faz | Principais saídas (no Drive) | Tempo |
|---|---|---|---|---|
| 01 | `01_dados` | Baixa as duas bases, aplica o pré-processamento, cria os splits e salva um cache `.npy`. | `cache/pv_*.npy`, `cache/pd_*.npy`, `cache/meta.json`, `results/figs/pv_vs_plantdoc.png` | ~10–15 min (CPU) |
| 02 | `02_treino` | Treina os 3 modelos no PlantVillage com aumento de dados leve (8 simetrias, recorte 80–100%, brilho/contraste). | `runs/<run>/best.keras`, `history.csv`, `metrics.json`, `results/metrics_02.csv` | GPU (L4/A100) |
| 03 | `03_avaliacao` | Gera as tabelas e figuras do artigo a partir dos runs do 02. Não treina nada. | `results/tables/*.csv/.tex`, `results/figs/*.png` | poucos min |
| 04 | `04_mitigacao` | Testa técnicas para reduzir a queda no PlantDoc (ver seção 4.3). | `runs/<run>_aug`, `_aug_ft`, `_adabn`, `_aug_adabn`; `results/tables/tab_mitigacao.*` | ~45 min (GPU) |
| 05 | `05_exportar` | Converte os modelos finais (`aug+ft`) para TFLite float32 e int8, avalia os `.tflite` e gera os arquivos do app. | `results/tflite/*.tflite`, `labels.txt`, `model_info.json`, `results/tables/tab_tflite.*` | ~10–15 min |

**Modo rápido (`QUICK = True`)** — os notebooks 02 e 04 têm uma flag no topo. Com ela ligada, o treino usa 10% dos
dados e 2 épocas e grava em `runs_quick/`, só para verificar que o código roda de ponta a ponta antes da execução
completa. Depois troque para `QUICK = False` e rode de novo.

## 4. Experimentos e avaliação

### 4.1 Protocolos de teste

Os modelos são avaliados sempre em dois conjuntos de teste que **nunca** são usados para treinar ou escolher
modelos:

- **PlantVillage (teste)** — 15% do PlantVillage, mesmo domínio do treino.
- **PlantDoc (teste oficial, 236 fotos)** — domínio de campo, avaliado de duas formas:
  - **Protocolo A (38 classes):** o modelo escolhe entre todas as 38 classes, como no app. É a métrica principal.
  - **Protocolo B (restrito):** a previsão fica limitada às 27 classes que existem no PlantDoc.

Métricas: acurácia, F1 macro, acurácia por cultura (acertar "tomate" mesmo errando a doença), ECE (erro de
calibração) e cobertura/acurácia com limiar de confiança 0,7.

### 4.2 Resultados dos modelos base (notebook 03)

| Modelo | Parâm. (M) | Treino (min) | Acc PV | F1 PV | Acc PD | F1 PD | Acc PD (restr.) |
|---|---|---|---|---|---|---|---|
| CNN própria | 1,18 | 14,5 | 99,3 | 99,2 | **13,1** | 11,2 | 14,4 |
| MobileNetV3-Small | 0,96 | 5,9 | 98,3 | 98,0 | 8,9 | 6,4 | 9,7 |
| MobileNetV2 (α=0,5) | 0,75 | 7,6 | 97,8 | 97,1 | 7,6 | 7,0 | 11,4 |

Quase perfeitos no laboratório, os três modelos caem para 8–13% em fotos de campo. O notebook 03 investiga
por quê:

- **Curvas de treino** — acurácia e perda por época.
- **Cultura × doença** — se o modelo pelo menos acerta a planta.
- **Matrizes de confusão** — 38×38 no PlantVillage; no PlantDoc, 27 classes reais × 38 previstas (mostra erros
  indo para classes que nem existem no PlantDoc).
- **F1 por classe no PlantDoc** — quais doenças ainda funcionam (ex.: ferrugem do milho) e quais não.
- **Confiança × acurácia** — cobertura e acurácia para cada limiar; daí vem o limiar de 70% do aviso de
  "foto incerta" no app.
- **Grad-CAM** — mapas de onde o modelo "olha"; se no PlantDoc o mapa cai no fundo, é sinal de que o modelo
  aprendeu atalhos do laboratório.

### 4.3 Mitigações (notebook 04)

Variantes testadas de forma acumulada para os três modelos:

| Variante | O que muda | Usa PlantDoc no treino? |
|---|---|---|
| `base` | modelo do notebook 02 | não |
| `base+adabn` | **AdaBN**: recalcula as estatísticas do BatchNorm com fotos de campo; nenhum peso é treinado | só imagens, sem rótulos |
| `aug` | treino do zero com **aumento forte** (recorte 12–100%, cor, desfoque, ruído) + **CutMix** + label smoothing 0,1 | não |
| `aug+adabn` | `aug` + AdaBN | só imagens, sem rótulos |
| `aug+ft` | `aug` + **fine-tuning** com fotos de campo rotuladas | sim (parte de treino) |

Para evitar vazamento de dados, o teste oficial do PlantDoc nunca é usado. AdaBN usa só as imagens de treino do
PlantDoc, sem rótulos. O fine-tuning usa 85% do treino do PlantDoc mais o mesmo número de imagens do
PlantVillage (para o modelo não esquecer o que já sabia) e guarda os outros 15% como validação.

Os modelos `aug+ft` são os exportados para o app.

### 4.4 Exportação e testes dos `.tflite` (notebook 05)

Os modelos são reconstruídos em float32 (o treino usou float16) e convertidos em duas versões:

- `<run>_fp32.tflite`: pesos float32 (referência).
- `<run>_int8.tflite`: quantização inteira de 8 bits em pesos e ativações, calibrada com 300 imagens. A entrada e
  a saída continuam float32, então o app usa o mesmo código para as duas versões.

Para cada arquivo, o notebook mede tamanho, acurácia (amostra de 2.000 imagens do teste do PlantVillage + teste
do PlantDoc) e latência na CPU do Colab. Na CPU x86 o int8 pode sair mais lento que o float32, porque o ganho
aparece em processadores ARM. Por isso a latência que vale é a medida **no celular**, pelo botão do app (seção 5).

## 5. App Android

App offline que tira (ou escolhe) uma foto de folha e mostra as 3 doenças mais prováveis.

- Inferência 100% no aparelho com **LiteRT** (TensorFlow Lite), sem internet e sem permissão de câmera
  (usa o app de câmera do sistema).
- Pré-processamento igual ao do treino: rotação EXIF, recorte quadrado central, redução para 128×128.
- Top-3 com nomes em português e aviso de **"foto incerta"** quando a maior probabilidade fica abaixo de 70%.
- Seletor com os **6 modelos** (3 arquiteturas × float32/int8), para comparar no próprio aparelho.
- **Botão de latência**: 10 execuções de aquecimento + 50 medidas com a foto atual (4 threads); mostra média e
  mediana em ms. É esse número que entra no artigo.

### Antes de compilar

Os modelos não ficam dentro de `app/`: o Gradle inclui a pasta `models/` da raiz do repositório como assets
(`assets.srcDirs = ['src/main/assets', '../../models']`). Ela precisa ter:

```
models/
├── labels.txt
├── cnn_base_fp32.tflite   cnn_base_int8.tflite
├── mnv3s_fp32.tflite      mnv3s_int8.tflite
└── mnv2_050_fp32.tflite   mnv2_050_int8.tflite
```

Esses arquivos são gerados pelo notebook 05 em `MyDrive/leafscan/results/tflite/`; copie-os para `models/`.

### Compilar e instalar

1. Abra a pasta **`app/`** (não a raiz do repositório) no Android Studio.
2. Espere a sincronização do Gradle terminar (na primeira vez ele baixa o Gradle 8.9 e as dependências).
3. No celular: ative as *Opções do desenvolvedor* e a *Depuração USB*, conecte no PC e clique em **Run**.
4. Para gerar o APK: *Build → Build App Bundle(s) / APK(s) → Build APK(s)*.
   O arquivo sai em `app/app/build/outputs/apk/`.

Requisitos: Android 7.0 (API 24) ou mais novo. Build: AGP 8.7, compileSdk 35, Java 17.
O build de release é assinado com a chave de debug só para facilitar a instalação do APK de teste.

### Código

Em `app/app/src/main/java/com/leafscan/`:

| Arquivo | Função |
|---|---|
| `MainActivity.java` | tela, câmera/galeria, seletor de modelo, resultado e medição de latência (inferência numa thread separada) |
| `Classifier.java` | carrega o `.tflite` e roda a inferência (entrada float32 RGB 0..255, saída 38 probabilidades) |
| `ImageUtils.java` | leitura da foto, correção EXIF, recorte central e redução para 128×128 |
| `Labels.java` | lê `labels.txt` e traduz as classes para português |
