// ES 3.20 permits read-write images only for r32f/r32i/r32ui.
// Exactly one invocation owns a pixel in all three planes. No neighbour reads.
layout(local_size_x = 8, local_size_y = 8) in;
layout(r32f, binding = 0) uniform highp image2D u_red;
layout(r32f, binding = 1) uniform highp image2D u_green;
layout(r32f, binding = 2) uniform highp image2D u_blue;

vec3 cs_load(ivec2 p) {
    return vec3(imageLoad(u_red, p).r, imageLoad(u_green, p).r, imageLoad(u_blue, p).r);
}
void cs_store(ivec2 p, vec3 value) {
    imageStore(u_red, p, vec4(value.r));
    imageStore(u_green, p, vec4(value.g));
    imageStore(u_blue, p, vec4(value.b));
}
