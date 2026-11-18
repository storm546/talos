These compact public-data fixtures retain timestamp and the twelve selected image
landmarks (x, y, visibility), followed by grid times and airborne probabilities.
Coordinates and aspect are rounded to Kotlin Float before the independent
`logs/ml/reference_forward.py` preprocessing and Double forward pass. The final
all-subject seed-0 model is used, at a fixed 33 fps for all three clips. These are
port checks, not accuracy measurements. No private owner data is included.

Twente: Huawei Wang, CC BY 4.0, https://zenodo.org/records/6644593.
Kaggle gesture dataset: publisher daffasesarabbani, CC BY 4.0,
https://www.kaggle.com/datasets/daffasesarabbani/gesture-video-dataset-for-jump-classification-1.
The webcam clip is from the training half. Only derived landmarks are retained.

- `twente-front.txt`: `twente-jumps9/Subj04_front/jump01.csv`; 87 observed poses, 87 grid samples.
- `twente-side.txt`: `twente-jumps9/Subj04_side/jump01.csv`; 87 observed poses, 87 grid samples.
- `webcam.txt`: `kaggle-landmarks/jump_in_place/137.csv.gz`; 64 observed poses, 70 grid samples.
