// #277 PR1: 重みテクスチャから出力1画素の1軸ぶんを計算する契約。仮の戻り値の実装は #279 が置き換える。
// s_weights: R32F（幅=最大タップ数、高さ=出力軸サイズ、不足列は0）。
// s_first: R32I（幅=1、高さ=出力軸サイズ）。horizontal は横軸なら true。
// 戻り値の R を平面、RGB を色として使う。タップ数は s_weights の幅から得る。
vec4 cs_resampleAxis(sampler2D s_input, sampler2D s_weights,
    isampler2D s_first, ivec2 outputPixel, bool horizontal) { return vec4(0.0); }
