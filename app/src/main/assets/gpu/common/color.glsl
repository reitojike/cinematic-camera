// #277 PR1: 関数名・引数だけを固定する。空の本体は #279 が実装する。
// include 用ファイル。#version と precision は呼び出し元に置く。
float cs_signedCbrt(float x) {}
vec3 cs_linearToLab(vec3 rgb) {}
vec3 cs_labToLinear(vec3 lab) {}
float cs_linearToUnit(float v) {}
float cs_unitToLinear(float v) {}
float cs_encode(float linear) {}
float cs_decode(float display) {}
float cs_hueDeg(vec3 lab) {}
float cs_band(float hue, float centre, float half) {}
float cs_skinWeight(float l, float c, float hueDeg) {}
