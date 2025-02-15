from sentence_transformers import SentenceTransformer

model = SentenceTransformer('moka-ai/m3e-base')

#Our sentences we like to encode
# sentences = [
#     '* Moka 此文本嵌入模型由 MokaAI 训练并开源，训练脚本使用 uniem',
#     '* Massive 此文本嵌入模型通过**千万级**的中文句对数据集进行训练',
#     '* Mixed 此文本嵌入模型支持中英双语的同质文本相似度计算，异质文本检索等功能，未来还会支持代码检索，ALL in one'
# ]

sentences = [
    '今天天气不错'
]

#Sentences are encoded by calling model.encode()
embeddings = model.encode(sentences)

#Print the embeddings
for sentence, embedding in zip(sentences, embeddings):
    print("Sentence:", sentence)
    print("Embedding:", embedding)
    print("")
