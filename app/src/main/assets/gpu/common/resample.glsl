// StageClearBlur.samples の正規化済み重みで、出力1画素の1軸ぶんを計算する。
// s_weights: R32F（幅=最大タップ数、高さ=出力軸サイズ、不足列は0）。
// s_first: R32I（幅=1、高さ=出力軸サイズ）。horizontal は横軸なら true。
// 戻り値の R を平面、RGB を色として使う。タップ数は s_weights の幅から得る。
vec4 cs_resampleAxis(sampler2D s_input, sampler2D s_weights,
    isampler2D s_first, ivec2 outputPixel, bool horizontal) {
    int destination = horizontal ? outputPixel.x : outputPixel.y;
    int first = texelFetch(s_first, ivec2(0, destination), 0).r;
    int taps = textureSize(s_weights, 0).x;
    precise vec4 value = vec4(0.0);
    for (int tap = 0; tap < taps; ++tap) {
        float weight = texelFetch(s_weights, ivec2(tap, destination), 0).r;
        // 右端・下端の短い行に足されたゼロ列では、入力の範囲外を fetch しない。
        if (weight == 0.0) continue;
        ivec2 sourcePixel = horizontal ? ivec2(first + tap, outputPixel.y) :
            ivec2(outputPixel.x, first + tap);
        value += texelFetch(s_input, sourcePixel, 0) * weight;
    }
    return value;
}
