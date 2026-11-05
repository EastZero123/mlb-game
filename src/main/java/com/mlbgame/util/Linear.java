package com.mlbgame.util;

/** 작은 연립방정식 풀이 (회귀·능력치 역산용, 크기 10 이하). */
public final class Linear {

    private Linear() {
    }

    /** 가우스 소거 (부분 피벗). 입력은 변경하지 않는다. */
    public static double[] solve(double[][] a0, double[] b0) {
        int n = b0.length;
        double[][] a = new double[n][];
        for (int i = 0; i < n; i++) {
            a[i] = a0[i].clone();
        }
        double[] b = b0.clone();
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(a[r][col]) > Math.abs(a[piv][col])) {
                    piv = r;
                }
            }
            double[] tr = a[col];
            a[col] = a[piv];
            a[piv] = tr;
            double tb = b[col];
            b[col] = b[piv];
            b[piv] = tb;
            if (Math.abs(a[col][col]) < 1e-12) {
                throw new IllegalStateException("특이 행렬");
            }
            for (int r = col + 1; r < n; r++) {
                double f = a[r][col] / a[col][col];
                for (int c = col; c < n; c++) {
                    a[r][c] -= f * a[col][c];
                }
                b[r] -= f * b[col];
            }
        }
        double[] x = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            double s = b[i];
            for (int c = i + 1; c < n; c++) {
                s -= a[i][c] * x[c];
            }
            x[i] = s / a[i][i];
        }
        return x;
    }
}
