package me.aias.example.utils.detection;

import ai.djl.modality.cv.BufferedImageFactory;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDArrays;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.index.NDIndex;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import org.bytedeco.javacpp.indexer.FloatRawIndexer;
import org.bytedeco.javacpp.indexer.IntRawIndexer;
import org.bytedeco.javacpp.indexer.UByteRawIndexer;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.*;
import org.opencv.core.CvType;

import java.util.Map;

import static org.bytedeco.opencv.global.opencv_imgproc.*;

public class OCRDetectionTranslator implements Translator<Image, NDList> {

    private Image image;
    private final int max_side_len;
    private final int max_candidates;
    private final int min_size;
    private final float box_thresh;
    private final float unclip_ratio;
    private float ratio_h;
    private float ratio_w;
    private int img_height;
    private int img_width;

    public OCRDetectionTranslator(Map<String, ?> arguments) {
        max_side_len =
                arguments.containsKey("max_side_len")
                        ? Integer.parseInt(arguments.get("max_side_len").toString())
                        : 960;
        max_candidates =
                arguments.containsKey("max_candidates")
                        ? Integer.parseInt(arguments.get("max_candidates").toString())
                        : 1000;
        min_size =
                arguments.containsKey("min_size")
                        ? Integer.parseInt(arguments.get("min_size").toString())
                        : 3;
        box_thresh =
                arguments.containsKey("box_thresh")
                        ? Float.parseFloat(arguments.get("box_thresh").toString())
                        : 0.5f;
        unclip_ratio =
                arguments.containsKey("unclip_ratio")
                        ? Float.parseFloat(arguments.get("unclip_ratio").toString())
                        : 1.6f;
    }

    @Override
    public NDList processOutput(TranslatorContext ctx, NDList list) {
        NDManager manager = ctx.getNDManager();
        NDArray pred = list.singletonOrThrow();
        pred = pred.squeeze();
        NDArray segmentation = pred.toType(DataType.UINT8, true).gt(0.3);   // thresh=0.3 .mul(255f)

        segmentation = segmentation.toType(DataType.UINT8, true);

        //convert from NDArray to Mat
        byte[] byteArray = segmentation.toByteArray();
        Shape shape = segmentation.getShape();
        int rows = (int) shape.get(0);
        int cols = (int) shape.get(1);

        Mat srcMat = new Mat(rows, cols, CvType.CV_8U);

        UByteRawIndexer ldIdx = srcMat.createIndexer();
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                ldIdx.put(row, col, byteArray[row * cols + col]);
            }
        }
        ldIdx.release();
        ldIdx.close();

        Mat mask = new Mat();
        // size 越小，腐蚀的单位越小，图片越接近原图
        Mat structImage =
                opencv_imgproc.getStructuringElement(opencv_imgproc.MORPH_RECT, new Size(2, 2));

        /**
         * 膨胀 膨胀说明： 图像的一部分区域与指定的核进行卷积， 求核的最`大`值并赋值给指定区域。 膨胀可以理解为图像中`高亮区域`的'领域扩大'。
         * 意思是高亮部分会侵蚀不是高亮的部分，使高亮部分越来越多。
         */
        opencv_imgproc.dilate(srcMat, mask, structImage);

        ldIdx = mask.createIndexer();
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                ldIdx.put(row, col, ldIdx.get(row, col) * 255);
            }
        }
        ldIdx.release();
        ldIdx.close();

        NDArray boxes = boxes_from_bitmap(manager, pred, mask, box_thresh);

        //boxes[:, :, 0] = boxes[:, :, 0] / ratio_w
        NDArray boxes1 = boxes.get(":, :, 0").div(ratio_w);
        boxes.set(new NDIndex(":, :, 0"), boxes1);
        //boxes[:, :, 1] = boxes[:, :, 1] / ratio_h
        NDArray boxes2 = boxes.get(":, :, 1").div(ratio_h);
        boxes.set(new NDIndex(":, :, 1"), boxes2);

        NDList dt_boxes = this.filter_tag_det_res(boxes);

        dt_boxes.detach();

        // release Mat
        srcMat.release();
        srcMat.close();
        mask.release();
        mask.close();
        structImage.release();
        structImage.close();

        return dt_boxes;
    }


    private NDList filter_tag_det_res(NDArray dt_boxes) {
        NDList boxesList = new NDList();

        int num = (int) dt_boxes.getShape().get(0);
        for (int i = 0; i < num; i++) {
            NDArray box = dt_boxes.get(i);
            box = order_points_clockwise(box);
            box = clip_det_res(box);
            float[] box0 = box.get(0).toFloatArray();
            float[] box1 = box.get(1).toFloatArray();
            float[] box3 = box.get(3).toFloatArray();
            int rect_width = (int) Math.sqrt(Math.pow(box1[0] - box0[0], 2) + Math.pow(box1[1] - box0[1], 2));
            int rect_height = (int) Math.sqrt(Math.pow(box3[0] - box0[0], 2) + Math.pow(box3[1] - box0[1], 2));
            if (rect_width <= 3 || rect_height <= 3)
                continue;
            boxesList.add(box);
        }

        return boxesList;
    }

    private NDArray clip_det_res(NDArray points) {
        for (int i = 0; i < points.getShape().get(0); i++) {
            int value = Math.max((int) points.get(i, 0).toFloatArray()[0], 0);
            value = Math.min(value, img_width - 1);
            points.set(new NDIndex(i + ",0"), value);
            value = Math.max((int) points.get(i, 1).toFloatArray()[0], 0);
            value = Math.min(value, img_height - 1);
            points.set(new NDIndex(i + ",1"), value);
        }

        return points;
    }

    /**
     * sort the points based on their x-coordinates
     * 顺时针
     *
     * @param pts
     * @return
     */

    private NDArray order_points_clockwise(NDArray pts) {
        NDList list = new NDList();
        long[] indexes = pts.get(":, 0").argSort().toLongArray();

        // grab the left-most and right-most points from the sorted
        // x-roodinate points
        Shape s1 = pts.getShape();
        NDArray leftMost1 = pts.get(indexes[0] + ",:");
        NDArray leftMost2 = pts.get(indexes[1] + ",:");
        NDArray leftMost = leftMost1.concat(leftMost2).reshape(2, 2);
        NDArray rightMost1 = pts.get(indexes[2] + ",:");
        NDArray rightMost2 = pts.get(indexes[3] + ",:");
        NDArray rightMost = rightMost1.concat(rightMost2).reshape(2, 2);

        // now, sort the left-most coordinates according to their
        // y-coordinates so we can grab the top-left and bottom-left
        // points, respectively
        indexes = leftMost.get(":, 1").argSort().toLongArray();
        NDArray lt = leftMost.get(indexes[0] + ",:");
        NDArray lb = leftMost.get(indexes[1] + ",:");
        indexes = rightMost.get(":, 1").argSort().toLongArray();
        NDArray rt = rightMost.get(indexes[0] + ",:");
        NDArray rb = rightMost.get(indexes[1] + ",:");

        list.add(lt);
        list.add(rt);
        list.add(rb);
        list.add(lb);

        NDArray rect = NDArrays.concat(list).reshape(4, 2);
        return rect;
    }

    /**
     * Get boxes from the binarized image predicted by DB
     *
     * @param manager
     * @param pred    the binarized image predicted by DB.
     * @param mask    new 'pred' after threshold filtering.
     */
    private NDArray boxes_from_bitmap(NDManager manager, NDArray pred, Mat mask, float box_thresh) {
        int dest_height = (int) pred.getShape().get(0);
        int dest_width = (int) pred.getShape().get(1);
        int height = mask.rows();
        int width = mask.cols();

        MatVector contours = new MatVector();
        Mat hierarchy = new Mat();
        // 寻找轮廓
        findContours(
                mask,
                contours,
                hierarchy,
                opencv_imgproc.RETR_LIST,
                opencv_imgproc.CHAIN_APPROX_SIMPLE,
                new Point(0, 0));

        int num_contours = Math.min((int) contours.size(), max_candidates);
        NDList boxList = new NDList();
//        NDArray boxes = manager.zeros(new Shape(num_contours, 4, 2), DataType.FLOAT32);
        float[] scores = new float[num_contours];

        int count = 0;
        for (int index = 0; index < num_contours; index++) {
            Mat contour = contours.get(index);
            float[][] pointsArr = new float[4][2];
            int sside = get_mini_boxes(contour, pointsArr);
            if (sside < this.min_size)
                continue;
            NDArray points = manager.create(pointsArr);
            float score = box_score_fast(manager, pred, points);
            if (score < this.box_thresh)
                continue;

            NDArray box = unclip(manager, points); // TODO get_mini_boxes(box)


            // box[:, 0] = np.clip(np.round(box[:, 0] / width * dest_width), 0, dest_width)
            NDArray boxes1 = box.get(":,0").div(width).mul(dest_width).round().clip(0, dest_width);
            box.set(new NDIndex(":, 0"), boxes1);
            // box[:, 1] = np.clip(np.round(box[:, 1] / height * dest_height), 0, dest_height)
            NDArray boxes2 = box.get(":,1").div(height).mul(dest_height).round().clip(0, dest_height);
            box.set(new NDIndex(":, 1"), boxes2);

            if (score > box_thresh) {
                boxList.add(box);
//                boxes.set(new NDIndex(count + ",:,:"), box);
                scores[index] = score;
                count++;
            }

            // release memory
            contour.release();
            contour.close();
        }
//        if (count < num_contours) {
//            NDArray newBoxes = manager.zeros(new Shape(count, 4, 2), DataType.FLOAT32);
//            newBoxes.set(new NDIndex("0,0,0"), boxes.get(":" + count + ",:,:"));
//            boxes = newBoxes;
//        }
        NDArray boxes = NDArrays.stack(boxList);

        // release
        hierarchy.release();
        hierarchy.close();
        contours.releaseReference();
        contours.close();

        return boxes;
    }

    /**
     * Shrink or expand the boxaccording to 'unclip_ratio'
     *
     * @param points The predicted box.
     * @return uncliped box
     */
    private NDArray unclip(NDManager manager, NDArray points) {
        points = order_points_clockwise(points);
        float[] pointsArr = points.toFloatArray();
        float[] lt = java.util.Arrays.copyOfRange(pointsArr, 0, 2);
        float[] lb = java.util.Arrays.copyOfRange(pointsArr, 6, 8);

        float[] rt = java.util.Arrays.copyOfRange(pointsArr, 2, 4);
        float[] rb = java.util.Arrays.copyOfRange(pointsArr, 4, 6);

        float width = distance(lt, rt);
        float height = distance(lt, lb);

        if (width > height) {
            float k = (lt[1] - rt[1]) / (lt[0] - rt[0]); // y = k * x + b

            float delta_dis = height;
            float delta_x = (float) Math.sqrt((delta_dis * delta_dis) / (k * k + 1));
            float delta_y = Math.abs(k * delta_x);

            if (k > 0) {
                pointsArr[0] = lt[0] - delta_x + delta_y;
                pointsArr[1] = lt[1] - delta_y - delta_x;
                pointsArr[2] = rt[0] + delta_x + delta_y;
                pointsArr[3] = rt[1] + delta_y - delta_x;

                pointsArr[4] = rb[0] + delta_x - delta_y;
                pointsArr[5] = rb[1] + delta_y + delta_x;
                pointsArr[6] = lb[0] - delta_x - delta_y;
                pointsArr[7] = lb[1] - delta_y + delta_x;
            } else {
                pointsArr[0] = lt[0] - delta_x - delta_y;
                pointsArr[1] = lt[1] + delta_y - delta_x;
                pointsArr[2] = rt[0] + delta_x - delta_y;
                pointsArr[3] = rt[1] - delta_y - delta_x;

                pointsArr[4] = rb[0] + delta_x + delta_y;
                pointsArr[5] = rb[1] - delta_y + delta_x;
                pointsArr[6] = lb[0] - delta_x + delta_y;
                pointsArr[7] = lb[1] + delta_y + delta_x;
            }
        } else {
            float k = (lt[1] - rt[1]) / (lt[0] - rt[0]); // y = k * x + b

            float delta_dis = width;
            float delta_y = (float) Math.sqrt((delta_dis * delta_dis) / (k * k + 1));
            float delta_x = Math.abs(k * delta_y);

            if (k > 0) {
                pointsArr[0] = lt[0] + delta_x - delta_y;
                pointsArr[1] = lt[1] - delta_y - delta_x;
                pointsArr[2] = rt[0] + delta_x + delta_y;
                pointsArr[3] = rt[1] - delta_y + delta_x;

                pointsArr[4] = rb[0] - delta_x + delta_y;
                pointsArr[5] = rb[1] + delta_y + delta_x;
                pointsArr[6] = lb[0] - delta_x - delta_y;
                pointsArr[7] = lb[1] + delta_y - delta_x;
            } else {
                pointsArr[0] = lt[0] - delta_x - delta_y;
                pointsArr[1] = lt[1] - delta_y + delta_x;
                pointsArr[2] = rt[0] - delta_x + delta_y;
                pointsArr[3] = rt[1] - delta_y - delta_x;

                pointsArr[4] = rb[0] + delta_x + delta_y;
                pointsArr[5] = rb[1] + delta_y - delta_x;
                pointsArr[6] = lb[0] + delta_x - delta_y;
                pointsArr[7] = lb[1] + delta_y + delta_x;
            }
        }
        points = manager.create(pointsArr).reshape(4, 2);

        return points;
    }

    private float distance(float[] point1, float[] point2) {
        float disX = point1[0] - point2[0];
        float disY = point1[1] - point2[1];
        float dis = (float) Math.sqrt(disX * disX + disY * disY);
        return dis;
    }

    /**
     * Get boxes from the contour or box.
     *
     * @param contour   The predicted contour.
     * @param pointsArr The predicted box.
     * @return smaller side of box
     */
    private int get_mini_boxes(Mat contour, float[][] pointsArr) {
        // https://blog.csdn.net/qq_37385726/article/details/82313558
        // bounding_box[1] - rect 返回矩形的长和宽
        RotatedRect rect = minAreaRect(contour);
        Mat points = new Mat();
        boxPoints(rect, points);

        FloatRawIndexer ldIdx = points.createIndexer();
        float[][] fourPoints = new float[4][2];
        for (int row = 0; row < 4; row++) {
            fourPoints[row][0] = ldIdx.get(row, 0);
            fourPoints[row][1] = ldIdx.get(row, 1);
        }
        ldIdx.release();
        ldIdx.close();

        float[] tmpPoint = new float[2];
        for (int i = 0; i < 4; i++) {
            for (int j = i + 1; j < 4; j++) {
                if (fourPoints[j][0] < fourPoints[i][0]) {
                    tmpPoint[0] = fourPoints[i][0];
                    tmpPoint[1] = fourPoints[i][1];
                    fourPoints[i][0] = fourPoints[j][0];
                    fourPoints[i][1] = fourPoints[j][1];
                    fourPoints[j][0] = tmpPoint[0];
                    fourPoints[j][1] = tmpPoint[1];
                }
            }
        }

        int index_1 = 0;
        int index_2 = 1;
        int index_3 = 2;
        int index_4 = 3;

        if (fourPoints[1][1] > fourPoints[0][1]) {
            index_1 = 0;
            index_4 = 1;
        } else {
            index_1 = 1;
            index_4 = 0;
        }

        if (fourPoints[3][1] > fourPoints[2][1]) {
            index_2 = 2;
            index_3 = 3;
        } else {
            index_2 = 3;
            index_3 = 2;
        }

        pointsArr[0] = fourPoints[index_1];
        pointsArr[1] = fourPoints[index_2];
        pointsArr[2] = fourPoints[index_3];
        pointsArr[3] = fourPoints[index_4];

        int height = rect.boundingRect().height();
        int width = rect.boundingRect().width();
        int sside = Math.min(height, width);


        // release
        points.release();
        points.close();
        rect.releaseReference();
        rect.close();

        return sside;
    }

    /**
     * Calculate the score of box.
     *
     * @param bitmap The binarized image predicted by DB.
     * @param points The predicted box
     * @return
     */
    private float box_score_fast(NDManager manager, NDArray bitmap, NDArray points) {
        NDArray box = points.get(":");
        long h = bitmap.getShape().get(0);
        long w = bitmap.getShape().get(1);
        // xmin = np.clip(np.floor(box[:, 0].min()).astype(np.int), 0, w - 1)
        int xmin = box.get(":, 0").min().floor().clip(0, w - 1).toType(DataType.INT32, true).toIntArray()[0];
        int xmax = box.get(":, 0").max().ceil().clip(0, w - 1).toType(DataType.INT32, true).toIntArray()[0];
        int ymin = box.get(":, 1").min().floor().clip(0, h - 1).toType(DataType.INT32, true).toIntArray()[0];
        int ymax = box.get(":, 1").max().ceil().clip(0, h - 1).toType(DataType.INT32, true).toIntArray()[0];

        NDArray mask = manager.zeros(new Shape(ymax - ymin + 1, xmax - xmin + 1), DataType.UINT8);

        box.set(new NDIndex(":, 0"), box.get(":, 0").sub(xmin));
        box.set(new NDIndex(":, 1"), box.get(":, 1").sub(ymin));

        //mask - convert from NDArray to Mat
        byte[] maskArray = mask.toByteArray();
        int rows = (int) mask.getShape().get(0);
        int cols = (int) mask.getShape().get(1);
        Mat maskMat = new Mat(rows, cols, CvType.CV_8U);
        UByteRawIndexer ldIdx = maskMat.createIndexer();
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                ldIdx.put(row, col, maskArray[row * cols + col]);
            }
        }
        ldIdx.release();
        ldIdx.close();

        //mask - convert from NDArray to Mat
        float[] boxArray = box.toFloatArray();
        Mat boxMat = new Mat(4, 2, CvType.CV_32S);
        IntRawIndexer intRawIndexer = boxMat.createIndexer();
        for (int row = 0; row < 4; row++) {
            intRawIndexer.put(row, 0, (int) boxArray[row * 2]);
            intRawIndexer.put(row, 1, (int) boxArray[row * 2 + 1]);
        }
        intRawIndexer.release();
        intRawIndexer.close();

//        boxMat.reshape(1, new int[]{1, 4, 2});
        MatVector matVector = new MatVector();
        matVector.put(boxMat);
        fillPoly(maskMat, matVector, new Scalar(1));


        NDArray subBitMap = bitmap.get(ymin + ":" + (ymax + 1) + "," + xmin + ":" + (xmax + 1));
        float[] subBitMapArr = subBitMap.toFloatArray();
        rows = (int) subBitMap.getShape().get(0);
        cols = (int) subBitMap.getShape().get(1);
        Mat bitMapMat = new Mat(rows, cols, CvType.CV_32F);
        FloatRawIndexer floatRawIndexer = bitMapMat.createIndexer();
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                floatRawIndexer.put(row, col, subBitMapArr[row * cols + col]);
            }
        }
        floatRawIndexer.release();
        floatRawIndexer.close();

        Scalar score = org.bytedeco.opencv.global.opencv_core.mean(bitMapMat, maskMat);
        float scoreValue = (float) score.get();
        // release
        maskMat.release();
        maskMat.close();
        boxMat.release();
        boxMat.close();
        bitMapMat.release();
        bitMapMat.close();
        matVector.releaseReference();
        matVector.close();
        score.releaseReference();
        score.close();

        return scoreValue;
    }

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDArray img = input.toNDArray(ctx.getNDManager());
        image = BufferedImageFactory.getInstance().fromNDArray(img);
        int h = input.getHeight();
        int w = input.getWidth();
        img_height = h;
        img_width = w;
        int resize_w = w;
        int resize_h = h;

        // limit the max side
        float ratio = 1.0f;
        if (Math.max(resize_h, resize_w) > max_side_len) {
            if (resize_h > resize_w) {
                ratio = (float) max_side_len / (float) resize_h;
            } else {
                ratio = (float) max_side_len / (float) resize_w;
            }
        }

        resize_h = (int) (resize_h * ratio);
        resize_w = (int) (resize_w * ratio);

        if (resize_h % 32 == 0) {
            resize_h = resize_h;
        } else if (Math.floor((float) resize_h / 32f) <= 1) {
            resize_h = 32;
        } else {
            resize_h = (int) Math.floor((float) resize_h / 32f) * 32;
        }

        if (resize_w % 32 == 0) {
            resize_w = resize_w;
        } else if (Math.floor((float) resize_w / 32f) <= 1) {
            resize_w = 32;
        } else {
            resize_w = (int) Math.floor((float) resize_w / 32f) * 32;
        }

        ratio_h = resize_h / (float) h;
        ratio_w = resize_w / (float) w;

        img = NDImageUtils.resize(img, resize_w, resize_h);
        img = NDImageUtils.toTensor(img);
        img =
                NDImageUtils.normalize(
                        img,
                        new float[]{0.485f, 0.456f, 0.406f},
                        new float[]{0.229f, 0.224f, 0.225f});
        img = img.expandDims(0);
        return new NDList(img);
    }

    @Override
    public Batchifier getBatchifier() {
        return null;
    }
}
