package com.example.jrme.face3dv3.fitting;

import android.graphics.Bitmap;
import android.util.Log;

import com.example.jrme.face3dv3.filters.convolution.SobelFilterGx;
import com.example.jrme.face3dv3.filters.convolution.SobelFilterGy;
import com.example.jrme.face3dv3.util.Pixel;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.RealMatrix;
import org.opencv.android.Utils;
import org.opencv.core.Mat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.example.jrme.face3dv3.util.IOHelper.readBinFloat;
import static com.example.jrme.face3dv3.util.IOHelper.readBinSFSV;
import static com.example.jrme.face3dv3.util.PixelUtil.getPixel;
import static java.lang.Math.abs;
import static java.lang.Math.pow;

/**
 * Created by JR on 2015/7/8.
 */
public class CostFunction {

    private static final String TAG = "CostFunction";

    private static final String CONFIG_DIRECTORY ="3DFace/DMM/config";
    private static final String SHAPE_DIRECTORY ="3DFace/DMM/Shape";
    private static final String TEXTURE_DIRECTORY ="3DFace/DMM/Texture";

    private static final String EIG_SHAPE_FILE = "eig_Shape.dat";
    private static final String EIG_TEXTURE_FILE = "eig_Texture.dat";
    private static final String INDEX83PT_FILE = "Featurepoint_Index.dat";
    private static final String SFSV_FILE = "subFeatureShapeVector.dat";

    private float E;
    private float sigmaI;
    private float sigmaF;

    private float[] alpha = new float[60];
    //private float[] beta = new float[60];
    private float[] eigValS;
    private float[] eigValT;
    private float[][] s; // eigenVector // BIG
    private float[][][] subFSV;

    private List<Pixel> average;
    private List<Pixel> input;
    private RealMatrix inputFeatPts;
    private RealMatrix modelFeatPts;
    //private int[] featPtsIndex;
    private int k;
    private Mat dxModel;
    private Mat dyModel;

    // range is [ 64140/3 ; 2 * 64140/3 ] for front face
    private int start = 21380, end = 42760; // we select 500 random values within this range
    private List<Integer> randomList = new ArrayList<>();

    /////////////////////////////////// Constructor ////////////////////////////////////////////////
    public CostFunction(List<Pixel> input, List<Pixel> average, RealMatrix inputFeatPts,
                        RealMatrix modelFeatPts, float [][] eigenVectors, Bitmap bmpModel, float sigmaI, float sigmaF) {

        //this.featPtsIndex = readBin83PtIndex(CONFIG_DIRECTORY, INDEX83PT_FILE);
        this.input = input;
        this.average = average;
        this.inputFeatPts = inputFeatPts;
        this.modelFeatPts = modelFeatPts;
        this.k = inputFeatPts.getRowDimension(); // should be equal to 83
        this.eigValS = readBinFloat(SHAPE_DIRECTORY, EIG_SHAPE_FILE, 60);
        this.eigValT = readBinFloat(TEXTURE_DIRECTORY, EIG_TEXTURE_FILE, 60);
        this.subFSV = readBinSFSV(CONFIG_DIRECTORY, SFSV_FILE);

        // Checking arguments
        if(input.isEmpty() || average.isEmpty()){
            throw new IllegalArgumentException("input or average list are empty");
        }
        if(input.size() != average.size()){
            throw new IllegalArgumentException("input and average list do not have the same size");
        }
        if(modelFeatPts.getRowDimension() != k || modelFeatPts.getColumnDimension() != inputFeatPts.getColumnDimension()){
            throw new IllegalArgumentException("inputFeatPts and modelFeatPts do not have the same size");
        }

        // Initialy populate list with 0, the value doesn't matter
        for (int h = 0; h < 500;h++) {
            this.randomList.add(0);
        }

        this.s = eigenVectors;

        // Initial Value
        alpha[0]= 1.0f / 60.0f;
        this.sigmaI = sigmaI;
        this.sigmaF = sigmaF;

        this.dxModel = computeSobelGx(bmpModel);
        this.dyModel = computeSobelGy(bmpModel);
        //saveBitmaptoPNG(TEXTURE_DIRECTORY, "modelFace2DGx.png", bmpModelGx); //save
        //saveBitmaptoPNG(TEXTURE_DIRECTORY, "modelFace2DGy.png", bmpModelGy); //save

        this.E = computeE();
    }
    ////////////////////////////////////////////////////////////////////////////////////////////////

    /////////////////////////////////// Getter /////////////////////////////////////////////////////
    public float[] getAlpha() {
        return alpha;
    }
    ////////////////////////////////////////////////////////////////////////////////////////////////

    /////////////////////////////////// Equation 21 ////////////////////////////////////////////////
    private float computeE(){
        float E = 0.0f, Ei, Ef;
        Log.d(TAG,"*");
        Log.d(TAG,"<E>");

        // Pick 500 Random Vertices Index each iteration
        Random r = new Random();
        for(int h =0; h< 500; h++){
            this.randomList.set(h, r.nextInt(end - start + 1) + start);
        }

        computeAlpha(); // Compute 60 alpha values
        Ei = computeEi();
        Ef = computeEf();
        E = (float) ((1/pow(sigmaI,2)) * Ei +
                (1/pow(sigmaF,2)) * Ef +
                sum_Alpha_eigValS() +
                sum_Beta_eigValT());

        Log.d(TAG,"sigmaI = "+ sigmaI);
        Log.d(TAG,"sigmaF = "+ sigmaF);
        Log.d(TAG,"sum_Alpha_eigValS() = "+ sum_Alpha_eigValS());
        Log.d(TAG,"sum_Beta_eigValT() = "+ sum_Beta_eigValT());
        Log.d(TAG,"Ei = "+ Ei);
        Log.d(TAG,"Ef = "+ Ef);
        Log.d(TAG,"E = " + E);
        Log.d(TAG,"</E>");
        Log.d(TAG,"*");

        return E;
    }

    private float sum_Alpha_eigValS(){
        float res = 0.0f;
        for(int i=0;i<60;i++){
            res += pow(alpha[i], 2)/pow(eigValS[i], 2);
        }
        return res;
    }

    private float sum_Beta_eigValT(){
        float res = 0.0f;
        for(int i=0;i<60;i++){
            res += pow(alpha[i], 2)/pow(eigValT[i], 2);
        }
        return res;
    }
    ////////////////////////////////////////////////////////////////////////////////////////////////

    /////////////////////////////////// Equation 17 ////////////////////////////////////////////////
    private float computeEi(){
        float res = 0.0f;

        for(int idx : randomList) {
            res += pow(Iinput(idx) - Imodel(idx),2);
        }
        return res;
    }
    ////////////////////////////////////////////////////////////////////////////////////////////////

    /////////////////////////////////// Equation 18 ////////////////////////////////////////////////
    private float computeEf(){
        float res = 0.0f;
        RealMatrix modelP = Pmodel();
        for(int j = 0; j < k; j++) {
            res += pow(inputFeatPts.getEntry(j,0) - modelP.getEntry(j,0), 2)
                    + pow(inputFeatPts.getEntry(j,1) - modelP.getEntry(j,1), 2);
        }
        return res;
    }
    ////////////////////////////////////////////////////////////////////////////////////////////////

    /////////////////////////////////// Calculate P Matrix of Equation 18 //////////////////////////
    private RealMatrix Pmodel(){
        RealMatrix res = new Array2DRowRealMatrix(83, 2);

        for(int j = 0; j< k ; j++){
            double X = modelFeatPts.getEntry(j, 0), Y = modelFeatPts.getEntry(j,1);
            for(int i =0; i<60; i++){
                X += alpha[i] * subFSV[i][j][0];
                Y += alpha[i] * subFSV[i][j][2];
            }
            res.setEntry(j, 0, X);
            res.setEntry(j, 1, Y);
        }
        return res;
    }
    ////////////////////////////////////////////////////////////////////////////////////////////////

    /////////////////////////////////// Equation 31 ////////////////////////////////////////////////
    private void computeAlpha(){

        float[] alphaStar = new float[60];
        float num, denum, lambda = 0.0001f; // initial 0.0001f;

        for(int i=0; i<60-1; i++){
            Log.d(TAG,"compute alpha, i = "+ i);
            Log.d(TAG,"alpha[" + i + "] = " + alpha[i]);

            num = (float) ((1/pow(sigmaI,2)) * deriv2Ei(i) * alpha[i]
                    + (1/pow(sigmaF,2)) * deriv2Ef(i) * alpha[i]
                    - (1/pow(sigmaI,2)) * derivEi(i) * alpha[i]
                    - (1/pow(sigmaF,2)) * derivEf(i) * alpha[i]
                    + (2/pow(eigValS[i],2)) * mean(alpha,i + 1));
            denum = (float) ((1/pow(sigmaI,2)) * deriv2Ei(i) * alpha[i]
                    + (1/pow(sigmaF,2)) * derivEf(i) * alpha[i]
                    + (2/pow(eigValS[i],2)));
            alphaStar[i] = num/denum;

            alpha[i+1] = alpha[i] + lambda * (alphaStar[i] - alpha[i]); // iteration
        }
        int last = alpha.length - 1;
        Log.d(TAG,"compute alpha, i = "+ last);
        Log.d(TAG,"alpha[" + last + "] = " + alpha[last]);

    }

    /*
     *  Mean of a float array between  range [0;end]
     */
    private float mean(float[] data, int end) {
        int sum = 0;
        for (int i = 0; i <end; i++) {
            sum += data[i];
        }
        return sum/end;
    }
    ////////////////////////////////////////////////////////////////////////////////////////////////

    /////////////////////////////////// Calculate Gray-Level I /////////////////////////////////////
    private float Iinput(int idx){
        float res;
        // Gray-Level calculation : I = 0.299R + 0.5876G + 0.114B
        res = 0.299f * input.get(idx).getR()
                + 0.5876f * input.get(idx).getG()
                + 0.114f * input.get(idx).getB();
        return res;
    }

    private float Imodel(int idx){
        float Imodel, tmp = 0.0f;
        Imodel = 0.299f * average.get(idx).getR()
                + 0.5876f * average.get(idx).getG()
                + 0.114f * average.get(idx).getB();
        for(int i =0; i<60; i++){
            tmp += alpha[i] * (s[idx * 3][i] + s[idx * 3 + 2][i]);
        }
        return Imodel + tmp;
    }
    ////////////////////////////////////////////////////////////////////////////////////////////////

    /////////////////////////////////// Derivation Function ////////////////////////////////////////
    private float derivEi(int i){
        float res = 0.0f;

        for(int idx : randomList) {
            res += -2.0f
                    * abs(Iinput(idx) - Imodel(idx))
                    * derivImodel(i, idx);
        }
        return res;
    }

    private float deriv2Ei(int i){
        float res = 0.0f;

        for(int idx : randomList) {
            res += 2.0f
                    * pow(derivImodel(i, idx), 2);
        }
        return res;
    }

    private float derivEf(int i){
        float res=0.0f, xIn, yIn, xA, yA, tmp = 0.0f;
        for(int j=0; j<k; j++) {
            xIn = (float) inputFeatPts.getEntry(j,0);
            yIn = (float) inputFeatPts.getEntry(j,1);
            xA = (float) modelFeatPts.getEntry(j,0);
            yA = (float) modelFeatPts.getEntry(j,1);

            for(int a =0; a < 60; a++){
                tmp += alpha[a] * (subFSV[a][j][0] + subFSV[a][j][2]);
            }
            res += -2.0f
                    * ((xIn + yIn) - (xA + yA) - tmp)
                    * alpha[i]
                    * (subFSV[i][j][0] + subFSV[i][j][2]);
        }
        return res;
    }

    private float deriv2Ef(int i){
        float res = 0.0f;
        for(int j=0; j<k; j++) {
            res += 2.0f * pow(alpha[i] * (subFSV[i][j][0] + subFSV[i][j][2]), 2);
        }
        return res;
    }

    private float derivImodel(int i, int idx){
        float Gx, Gy;
        float res;

        int x = average.get(idx).getX();
        int y = average.get(idx).getY();

        Gx = (float) dxModel.get(x,y)[0];
        Gy = (float) dyModel.get(x,y)[0];

        res = Gx * s[idx * 3][i] + Gy * s[idx * 3 + 2][i];

        return res;
    }
    ////////////////////////////////////////////////////////////////////////////////////////////////

    /////////////////////////////////// Apply Sobel Filter /////////////////////////////////////////
    private Mat computeSobelGx(Bitmap srcBmp) {
        Mat src = new Mat();

        // convert Bmp to Mat
        Utils.bitmapToMat(srcBmp, src);

        SobelFilterGx sobelGx = new SobelFilterGx();
        sobelGx.apply(src, src);

        return sobelGx.getGrad_x();
    }

    private Mat computeSobelGy(Bitmap srcBmp) {
        Mat src = new Mat();

        // convert Bmp to Mat
        Utils.bitmapToMat(srcBmp, src);

        SobelFilterGy sobelGy = new SobelFilterGy();
        sobelGy.apply(src, src);

        return sobelGy.getGrad_y();
    }
    ////////////////////////////////////////////////////////////////////////////////////////////////

}
