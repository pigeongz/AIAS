package me.aias.sd;

import ai.djl.Device;
import ai.djl.ModelException;
import ai.djl.modality.cv.Image;
import ai.djl.opencv.OpenCVImageFactory;
import ai.djl.translate.TranslateException;
import me.aias.sd.controlnet.SegUperNetDetector;
import me.aias.sd.pipelines.StableDiffusionControlNetPipeline;
import me.aias.sd.utils.ImageUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class ControlNetSegCpu {

    private ControlNetSegCpu() {}

    public static void main(String[] args) throws ModelException, IOException, TranslateException {

        Path imageFile = Paths.get("src/test/resources/seg.png");
        Image image = OpenCVImageFactory.getInstance().fromFile(imageFile);
        String prompt = "old house in stormy weather with rain and wind";

        try (StableDiffusionControlNetPipeline model = new StableDiffusionControlNetPipeline("models/pytorch_cpu/", "controlnet_seg.pt", Device.cpu());
             SegUperNetDetector detector = new SegUperNetDetector(512, 512, false, Device.cpu())) {
            Image img = detector.predict(image);

            long start = System.currentTimeMillis();

            Image result = model.generateImage(img, prompt, "", 25);

            long end = System.currentTimeMillis();
            System.out.println("Time: " + (end - start)/1000f/60f);


            ImageUtils.saveImage(result, "ctrlnet_seg_cpu.png", "output");
        }

    }
}