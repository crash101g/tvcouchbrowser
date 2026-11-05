"""Step 2: the 3D scenes, rendered with Blender Cycles.
Run: blender -b -P tools/art/scene.py -- <shot> <width> <samples> <out.png>
Shots are listed in SHOTS at the bottom. Everything is modelled here; nothing is downloaded."""
import math, random, sys
from pathlib import Path
import bpy, bmesh
from mathutils import Vector, Matrix

BUILD = Path(__file__).resolve().parent / "build"
BLUE, VIOLET, CYAN, AMBER, MAGENTA = (.05,.32,1,1), (.32,.1,1,1), (.05,.75,1,1), (1,.42,.08,1), (1,.06,.42,1)

# ---- materials ----
def bsdf(name, color, rough=.5, metal=0, sheen=0, bump=0, bump_scale=300):
    m = bpy.data.materials.new(name); m.use_nodes = True
    n = m.node_tree.nodes; b = n["Principled BSDF"]
    b.inputs["Base Color"].default_value = color
    b.inputs["Roughness"].default_value = rough
    b.inputs["Metallic"].default_value = metal
    b.inputs["Sheen Weight"].default_value = sheen
    if bump:
        t = n.new("ShaderNodeTexNoise"); t.inputs["Scale"].default_value = bump_scale; t.inputs["Detail"].default_value = 6
        h = n.new("ShaderNodeBump"); h.inputs["Strength"].default_value = bump; h.inputs["Distance"].default_value = .004
        m.node_tree.links.new(t.outputs[0], h.inputs["Height"]); m.node_tree.links.new(h.outputs[0], b.inputs["Normal"])
    return m

def glow(name, color, strength):
    m = bpy.data.materials.new(name); m.use_nodes = True
    t = m.node_tree; t.nodes.clear()
    e = t.nodes.new("ShaderNodeEmission"); e.inputs[0].default_value = color; e.inputs[1].default_value = strength
    o = t.nodes.new("ShaderNodeOutputMaterial"); t.links.new(e.outputs[0], o.inputs[0])
    return m

def glow_image(name, file, strength):
    m = glow(name, (1,1,1,1), strength); t = m.node_tree
    i = t.nodes.new("ShaderNodeTexImage"); i.image = bpy.data.images.load(str(BUILD / file))
    i.extension = "EXTEND"
    t.links.new(i.outputs[0], t.nodes["Emission"].inputs[0])
    return m

def floor_mat():
    m = bsdf("floor", (.03,.02,.016,1), .2)
    t = m.node_tree; n = t.nodes; b = n["Principled BSDF"]
    br = n.new("ShaderNodeTexBrick"); br.inputs["Scale"].default_value = 1.2; br.inputs["Mortar Size"].default_value = .004
    br.inputs["Color1"].default_value = (.05,.03,.02,1); br.inputs["Color2"].default_value = (.022,.014,.01,1)
    br.inputs["Mortar"].default_value = (.002,.002,.002,1); br.offset = .5
    br.inputs["Brick Width"].default_value = 2.2; br.inputs["Row Height"].default_value = .2
    c = n.new("ShaderNodeTexCoord"); t.links.new(c.outputs["Object"], br.inputs["Vector"])
    t.links.new(br.outputs[0], b.inputs["Base Color"])
    nz = n.new("ShaderNodeTexNoise"); nz.inputs["Scale"].default_value = 9; nz.inputs["Detail"].default_value = 8
    r = n.new("ShaderNodeMapRange"); r.inputs["To Min"].default_value = .1; r.inputs["To Max"].default_value = .38
    t.links.new(nz.outputs[0], r.inputs["Value"]); t.links.new(r.outputs[0], b.inputs["Roughness"])
    return m

# ---- modelling helpers ----
def obj(name, mesh, mat=None, loc=(0,0,0), rot=(0,0,0), smooth=True):
    o = bpy.data.objects.new(name, mesh); bpy.context.collection.objects.link(o)
    o.location = loc; o.rotation_euler = rot
    if mat: mesh.materials.append(mat)
    if smooth:
        for p in mesh.polygons: p.use_smooth = True
    return o

def box(name, size, loc, mat, r=.01, seg=4, rot=(0,0,0), cuts=0, puff=None, wrinkle=0):
    """Rounded box. puff=(top, front) bulges those faces, for cushions."""
    bm = bmesh.new(); bmesh.ops.create_cube(bm, size=1)
    if cuts: bmesh.ops.subdivide_edges(bm, edges=bm.edges[:], cuts=cuts, use_grid_fill=True)
    for v in bm.verts:
        x, y, z = v.co * 2; v.co.x *= size[0]; v.co.y *= size[1]; v.co.z *= size[2]
        if puff:
            v.co.z += puff[0] * max(z,0) * (1-x*x) * (1-y*y)
            v.co.y += puff[1] * max(y,0) * (1-x*x) * (1-z*z)
    me = bpy.data.meshes.new(name); bm.to_mesh(me); bm.free()
    o = obj(name, me, mat, loc, rot)
    if r:
        b = o.modifiers.new("b", "BEVEL"); b.width = r; b.segments = seg; b.limit_method = "ANGLE"; b.angle_limit = math.radians(30); b.use_clamp_overlap = True
    if cuts or puff:
        s = o.modifiers.new("s", "SUBSURF"); s.levels = s.render_levels = 2
    if wrinkle:
        t = bpy.data.textures.new(name, "CLOUDS"); t.noise_scale = .12; t.noise_depth = 3
        d = o.modifiers.new("d", "DISPLACE"); d.texture = t; d.strength = wrinkle; d.mid_level = .5
    return o

def cyl(name, r, h, loc, mat, rot=(0,0,0), verts=32):
    bm = bmesh.new(); bmesh.ops.create_cone(bm, cap_ends=True, segments=verts, radius1=r, radius2=r, depth=h)
    me = bpy.data.meshes.new(name); bm.to_mesh(me); bm.free()
    o = obj(name, me, mat, loc, rot)
    w = o.modifiers.new("b", "BEVEL"); w.width = min(r, h) * .2; w.segments = 3; w.limit_method = "ANGLE"
    return o

def screen(name, w, h, loc, mat, rot=(0,0,0), corner=0.0):
    """A rectangle with image UVs, drawn in its XZ plane and facing -Y."""
    n = 28 if corner else 1
    pts = []
    if corner:
        for cx, cz, a0 in ((w/2-corner,h/2-corner,0),(-w/2+corner,h/2-corner,90),(-w/2+corner,-h/2+corner,180),(w/2-corner,-h/2+corner,270)):
            for i in range(n//4+1):
                a = math.radians(a0 + 90*i/(n//4)); pts.append((cx+corner*math.cos(a), cz+corner*math.sin(a)))
        pts = pts[::-1]
    else:
        pts = [(w/2,h/2),(w/2,-h/2),(-w/2,-h/2),(-w/2,h/2)]
    me = bpy.data.meshes.new(name)
    me.from_pydata([(x,0,z) for x,z in pts], [], [list(range(len(pts)))])
    uv = me.uv_layers.new()
    for l in me.loops:
        x, z = pts[l.vertex_index]; uv.data[l.index].uv = (x/w+.5, z/h+.5)
    return obj(name, me, mat, loc, rot, smooth=False)

# ---- the couch ----
def couch(fabric, x=0, y=0, rz=0, pillows=True):
    root = bpy.data.objects.new("couch", None); bpy.context.collection.objects.link(root)
    root.location = (x, y, 0); root.rotation_euler = (0, 0, rz)
    metal = bsdf("leg", (.75,.55,.3,1), .3, 1)
    parts = [
        box("base", (2.2,.92,.2), (0,0,.23), fabric, .05, cuts=2),
        box("armL", (.24,.98,.56), (-1.08,0,.41), fabric, .1, cuts=3, puff=(.03,0), wrinkle=.006),
        box("armR", (.24,.98,.56), (1.08,0,.41), fabric, .1, cuts=3, puff=(.03,0), wrinkle=.006),
        box("back", (2.0,.24,.72), (0,-.37,.5), fabric, .1, cuts=3, puff=(.03,0)),
    ]
    for i in (-1,0,1):
        parts.append(box(f"seat{i}", (.635,.72,.15), (i*.645,.09,.40), fabric, .05, cuts=6, puff=(.05,.015), wrinkle=.01))
        parts.append(box(f"cush{i}", (.63,.2,.42), (i*.645,-.17,.70), fabric, .08, cuts=6, puff=(.02,.07), wrinkle=.012, rot=(math.radians(-12),0,0)))
    for sx, sy in ((-1,-1),(1,-1),(-1,1),(1,1)):
        parts.append(cyl("leg", .022, .14, (sx*1.05,sy*.38,.07), metal, rot=(sy*.12,-sx*.12,0)))
    if pillows:
        amber, mag = bsdf("amberp", (.9,.3,.04,1), .7, sheen=.6, bump=.4), bsdf("magp", (.8,.05,.3,1), .7, sheen=.6, bump=.4)
        parts.append(box("p1", (.42,.14,.42), (-.72,.06,.70), amber, .05, cuts=6, puff=(.02,.08), wrinkle=.012, rot=(math.radians(-22),math.radians(8),math.radians(12))))
        parts.append(box("p2", (.42,.14,.42), (.78,.06,.70), mag, .05, cuts=6, puff=(.02,.08), wrinkle=.012, rot=(math.radians(-20),math.radians(-10),math.radians(-14))))
    for p in parts: p.parent = root
    return root

# ---- the phone and remote ----
def phone(loc, rot, strength=1.6):
    root = bpy.data.objects.new("phone", None); bpy.context.collection.objects.link(root)
    root.location = loc; root.rotation_euler = rot
    body = box("phonebody", (.076,.009,.156), (0,0,0), bsdf("alu", (.05,.06,.08,1), .25, 1), .004)
    glass = box("phoneglass", (.0755,.001,.1555), (0,-.0048,0), bsdf("black", (0,0,0,1), .05), .0035)
    s = screen("phonescreen", .0705, .1505, (0,-.0056,0), glow_image("phoneimg", "phone-top.png", strength), corner=.0075)
    for p in (body, glass, s): p.parent = root
    return root

def remote(loc, rot):
    root = bpy.data.objects.new("remote", None); bpy.context.collection.objects.link(root)
    root.location = loc; root.rotation_euler = rot
    plastic = bsdf("rem", (.012,.013,.016,1), .35, bump=.05)
    key = bsdf("key", (.05,.055,.065,1), .4)
    white = glow("okglow", (.2,.55,1,1), 5)
    parts = [box("rembody", (.048,.17,.016), (0,0,0), plastic, .0065),
             cyl("dpad", .0185, .003, (0,.038,.0085), key, verts=64),
             cyl("ok", .0085, .004, (0,.038,.0098), white, verts=48)]
    for ix in (-1,0,1):
        for iy in range(5):
            parts.append(cyl("k", .0042, .0025, (ix*.0125,-.002-iy*.0135,.0083), key))
    for i in range(4):
        a = i*math.pi/2 + math.pi/4
        parts.append(box("tick", (.0035,.0012,.0008), (.0135*math.cos(a),.038+.0135*math.sin(a),.0105), glow("t",(.6,.8,1,1),2), 0, rot=(0,0,a)))
    parts.append(cyl("led", .0016, .001, (0,.073,.0082), glow("led",(1,.1,.1,1),8), verts=16))
    for p in parts: p.parent = root
    return root

# ---- ads breaking apart ----
def split(poly, rng, n):
    if n <= 1: return [poly]
    c = sum(poly, Vector((0,0))) / len(poly)
    d = Vector((1,0)); d.rotate(Matrix.Rotation(rng.uniform(0,math.pi), 2)); nrm = Vector((-d.y, d.x))
    a, b = [], []
    for i, p in enumerate(poly):
        q = poly[(i+1) % len(poly)]; sp, sq = (p-c).dot(nrm), (q-c).dot(nrm)
        (a if sp >= 0 else b).append(p)
        if sp * sq < 0:
            x = p + (q-p) * (sp / (sp - sq)); a.append(x); b.append(x)
    if len(a) < 3 or len(b) < 3: return [poly]
    k = n // 2
    return split(a, rng, n-k) + split(b, rng, k)

def shatter(anchor, direction, size, cell, mat, length, seed, n=30):
    """Cuts one pretend ad into n pieces and streams them away from anchor along direction.
    cell=(col,row) picks an ad in ad-cards.png."""
    rng = random.Random(seed); w, h = size; d = Vector(direction).normalized()
    side = d.cross(Vector((0,0,1))).normalized(); up = side.cross(d)
    rect = [Vector((-w/2,-h/2)), Vector((w/2,-h/2)), Vector((w/2,h/2)), Vector((-w/2,h/2))]
    for shard in split(rect, rng, n):
        mid = sum(shard, Vector((0,0))) / len(shard)
        t = rng.random() ** 1.4
        grow = .9 + t * 1.3        # pieces further from the TV are closer to the camera
        me = bpy.data.meshes.new("shard"); bm = bmesh.new()
        f = bm.faces.new([bm.verts.new(((p.x-mid.x)*grow, 0, (p.y-mid.y)*grow)) for p in shard])
        uvl = bm.loops.layers.uv.new()
        for l, p in zip(f.loops, shard):
            l[uvl].uv = ((cell[0] + p.x/w + .5) / 2, (1 - cell[1] + p.y/h + .5) / 2)
        bmesh.ops.solidify(bm, geom=bm.faces[:], thickness=.01)
        bm.to_mesh(me); bm.free()
        spread = .08 + t * .5
        pos = Vector(anchor) + d * (t * length) + side * rng.gauss(0, spread) + up * rng.gauss(0, spread)
        a = .25 + t * 1.3
        obj("shard", me, mat, pos, (rng.uniform(-a,a), rng.uniform(-a,a), rng.uniform(-a,a)), smooth=False)

# ---- the living room ----
TV_W, TV_H, TV_Z, TV_Y = 1.72, .97, 1.26, 3.1

def room(tv_image="tv-start.png", tv_strength=1, shards=False, tv_light=1.0):
    floor = bpy.data.meshes.new("floor"); floor.from_pydata([(-6,-7,0),(6,-7,0),(6,5,0),(-6,5,0)], [], [[0,1,2,3]])
    obj("floor", floor, floor_mat(), smooth=False)
    wall = bsdf("wall", (.012,.014,.02,1), .7)
    for name, s, l in (("back",(12,.1,3),(0,3.3,1.5)),("left",(.1,12,3),(-3.6,0,1.5)),("right",(.1,12,3),(3.6,0,1.5)),("ceil",(12,12,.1),(0,0,2.95)),("rear",(12,.1,3),(0,-5,1.5))):
        box(name, s, l, wall, 0)
    slat = bsdf("slat", (.08,.045,.025,1), .42, bump=.15, bump_scale=60)
    proto = box("slat", (.052,.04,2.95), (0,3.23,1.475), slat, .004).data
    for i in range(-44,45):
        if i == 0: continue      # the prototype itself
        s = bpy.data.objects.new("s", proto); bpy.context.collection.objects.link(s); s.location = (i*.08,3.23,1.475)
        s.modifiers.new("b","BEVEL").width = .004
    # TV, and the bias light behind it washing over the slats
    box("tvbody", (TV_W+.03,.045,TV_H+.03), (0,TV_Y,TV_Z), bsdf("tv",(.004,.004,.005,1),.2), .006)
    screen("tvscreen", TV_W, TV_H, (0,TV_Y-.0235,TV_Z), glow_image("tvimg", tv_image, tv_strength))
    box("biasL", (.03,.02,TV_H-.1), (-TV_W/2+.04,TV_Y+.06,TV_Z), glow("bL",BLUE,70), 0)
    box("biasR", (.03,.02,TV_H-.1), (TV_W/2-.04,TV_Y+.06,TV_Z), glow("bR",VIOLET,70), 0)
    box("biasT", (TV_W-.1,.02,.03), (0,TV_Y+.06,TV_Z+TV_H/2-.04), glow("bT",CYAN,50), 0)
    box("biasB", (TV_W-.1,.02,.03), (0,TV_Y+.06,TV_Z-TV_H/2+.04), glow("bB",MAGENTA,50), 0)
    # the screen's light on the room: stronger than the image can be without washing it out, so it's a hidden light
    l = bpy.data.lights.new("tvlight", "AREA"); l.shape = "RECTANGLE"; l.size = TV_W; l.size_y = TV_H
    l.energy = 230 * tv_light; l.color = (.55,.68,1)
    o = bpy.data.objects.new("tvlight", l); bpy.context.collection.objects.link(o)
    o.location = (0,TV_Y-.05,TV_Z); o.rotation_euler = (math.radians(-90),0,0); o.visible_camera = False
    o.visible_glossy = False
    # console, rug, floor lamp
    wood = bsdf("console", (.03,.016,.01,1), .35, bump=.1, bump_scale=40)
    box("console", (2.5,.42,.34), (0,3.0,.36), wood, .012)
    box("led", (2.42,.02,.01), (0,3.1,.175), glow("ledc",MAGENTA,14), 0)
    box("rug", (3.6,2.8,.012), (0,.4,.006), bsdf("rug",(.03,.04,.06,1),.95,bump=1,bump_scale=500), .004)
    brass = bsdf("brass",(.8,.55,.25,1),.3,1)
    cyl("lampbase", .15, .025, (1.95,.1,.0125), brass, verts=64)
    cyl("lamppole", .012, 1.6, (1.95,.1,.8), brass)
    cyl("lampshade", .19, .3, (1.95,.1,1.68), glow("shade",(1,.36,.08,1),1.15), verts=64)
    l = bpy.data.lights.new("lamp","POINT"); l.energy = 45; l.color = (1,.5,.18); l.shadow_soft_size = .15
    o = bpy.data.objects.new("lamp", l); bpy.context.collection.objects.link(o); o.location = (1.95,.1,1.45)
    wash = bpy.data.lights.new("wash", "AREA"); wash.size = 6; wash.size_y = .4; wash.shape = "RECTANGLE"; wash.energy = 40; wash.color = (.8,.6,.5)
    o = bpy.data.objects.new("wash", wash); bpy.context.collection.objects.link(o); o.location = (0,2.75,2.85); o.rotation_euler = (math.radians(-12),0,0)
    if shards == "corners":
        ad = glow_image("ads", "ad-cards.png", 1)
        for anchor, d, cell, seed in (((-.84,3.02,1.72),(-.75,-.62,.34),(0,0),3), ((.84,3.02,1.72),(.75,-.62,.34),(1,0),5),
                                      ((-.84,3.02,.8),(-.8,-.58,-.2),(1,1),11), ((.84,3.02,.8),(.8,-.6,-.2),(0,1),17)):
            shatter(anchor, d, (.6,.45), cell, ad, 2.1, seed)
    elif shards == "left":
        ad = glow_image("ads", "ad-cards.png", 1)
        for anchor, d, cell, seed in (((-.6,3.0,1.55),(-.62,-.72,.3),(0,0),3), ((-.6,3.0,1.0),(-.7,-.7,-.12),(1,0),11), ((-.84,3.0,1.25),(-.8,-.6,.04),(0,1),5)):
            shatter(anchor, d, (.5,.38), cell, ad, 1.7, seed, n=26)

def fabric_mat(): return bsdf("fabric", (.012,.05,.3,1), .72, sheen=.15, bump=.5)

def studio():
    """The couch alone, in front of a neon TV outline: the app's logo."""
    floor = bpy.data.meshes.new("f"); floor.from_pydata([(-8,-8,0),(8,-8,0),(8,8,0),(-8,8,0)],[],[[0,1,2,3]])
    obj("f", floor, bsdf("gloss",(.004,.005,.01,1),.12), smooth=False)
    box("w", (16,.1,8), (0,1.3,4), bsdf("wall",(.01,.012,.03,1),.5), 0)
    c = bpy.data.curves.new("tv", "CURVE"); c.dimensions = "3D"; c.bevel_depth = .05; c.bevel_resolution = 6
    W, H, R, Z, Y = 3.1, 1.76, .16, 1.24, .75
    pts = []
    for cx, cz, a0 in ((W/2-R,H/2-R,0),(-W/2+R,H/2-R,90),(-W/2+R,-H/2+R,180),(W/2-R,-H/2+R,270)):
        for i in range(9):
            a = math.radians(a0 + 90*i/8); pts.append((cx+R*math.cos(a), Y, Z+cz+R*math.sin(a), 1))
    sp = c.splines.new("POLY"); sp.points.add(len(pts)-1); sp.use_cyclic_u = True
    for p, q in zip(sp.points, pts): p.co = q
    m = glow("neon",(1,1,1,1),2.2); t = m.node_tree
    tc = t.nodes.new("ShaderNodeTexCoord"); sep = t.nodes.new("ShaderNodeSeparateXYZ"); rmp = t.nodes.new("ShaderNodeValToRGB")
    add = t.nodes.new("ShaderNodeMath"); add.operation = "ADD"
    mul = t.nodes.new("ShaderNodeMath"); mul.operation = "MULTIPLY"; mul.inputs[1].default_value = .22
    t.links.new(tc.outputs["Object"], sep.inputs[0]); t.links.new(sep.outputs[0], add.inputs[0]); t.links.new(sep.outputs[2], add.inputs[1])
    t.links.new(add.outputs[0], mul.inputs[0])
    off = t.nodes.new("ShaderNodeMath"); off.operation = "ADD"; off.inputs[1].default_value = .2
    t.links.new(mul.outputs[0], off.inputs[0]); t.links.new(off.outputs[0], rmp.inputs[0])
    rmp.color_ramp.elements[0].color = (.05,.8,1,1); rmp.color_ramp.elements[1].color = (1,.05,.55,1)
    e = rmp.color_ramp.elements.new(.55); e.color = (.3,.12,1,1)
    t.links.new(rmp.outputs[0], t.nodes["Emission"].inputs[0])
    c.materials.append(m); o = bpy.data.objects.new("tv", c); bpy.context.collection.objects.link(o)
    couch(bsdf("fabric",(.01,.12,.85,1),.6,sheen=.2,bump=.4), y=-.25, rz=math.pi)
    for name, loc, en, col, size in (("key",(-1.6,-3.4,3.4),300,(.8,.9,1),3),("fill",(2.6,-2.8,1.3),90,(1,.4,.8),2.5)):
        l = bpy.data.lights.new(name,"AREA"); l.size = size; l.energy = en; l.color = col
        o = bpy.data.objects.new(name,l); bpy.context.collection.objects.link(o); o.location = loc
        o.rotation_euler = (Vector((0,0,.6))-Vector(loc)).to_track_quat("-Z","Y").to_euler()

# ---- cameras ----
def camera(loc, target, lens, fstop=None, focus=None, shift=(0,0)):
    cd = bpy.data.cameras.new("c"); cd.lens = lens; cd.sensor_width = 36; cd.shift_x, cd.shift_y = shift
    o = bpy.data.objects.new("c", cd); bpy.context.collection.objects.link(o)
    o.location = loc; o.rotation_euler = (Vector(target)-Vector(loc)).to_track_quat("-Z","Y").to_euler()
    bpy.context.scene.camera = o
    if fstop:
        cd.dof.use_dof = True; cd.dof.aperture_fstop = fstop; cd.dof.aperture_blades = 7
        cd.dof.focus_distance = (Vector(focus or target)-Vector(loc)).length

def hidden_light(name, loc, target, energy, color, size=2):
    l = bpy.data.lights.new(name, "AREA"); l.size = size; l.energy = energy; l.color = color
    o = bpy.data.objects.new(name, l); bpy.context.collection.objects.link(o); o.location = loc
    o.rotation_euler = (Vector(target)-Vector(loc)).to_track_quat("-Z","Y").to_euler()
    o.visible_camera = False

def shot_hero():
    room(shards="corners"); couch(fabric_mat(), y=-.1)
    remote((-1.08,-.16,.735), (0,0,math.radians(-28)))
    hidden_light("rim", (-3,-2.6,2.6), (.2,0,.7), 45, (.45,.6,1), 3)
    camera((-2.45,-1.95,1.85), (.15,1.7,1.0), 21, shift=(-.17,0))

def shot_ads():
    room("tv-split.png", 1, shards="left", tv_light=.7); couch(fabric_mat(), y=.1)
    camera((1.0,.25,1.3), (-.1,3.1,1.28), 38)

def shot_phone():
    room(tv_light=1.2); couch(fabric_mat(), y=.1)
    p = (-.11,-.5,1.035)
    phone(p, (math.radians(-12),math.radians(-7),math.radians(-16)), 1)
    hidden_light("kick", (-.9,-1.2,1.5), p, 8, (.5,.65,1), 1)
    camera((.0,-1.14,1.03), (.06,3,1.1), 45, 4, p)

def shot_remote():
    room(tv_light=1.2); couch(fabric_mat(), y=.1, pillows=False)
    p = (-1.08,.1,.735)
    remote(p, (0,0,math.radians(-24)))
    hidden_light("kick", (-1.7,-.6,1.5), p, 7, (.6,.75,1), .8)
    camera((-1.31,-.53,.98), (-.84,1.8,.66), 30, 2.8, p)

def shot_logo():
    studio(); camera((0,-6.2,1.5), (0,0,1.02), 62)

def shot_banner():
    studio(); camera((0,-7,1.5), (0,0,1.05), 36, shift=(.2,0))

SHOTS = dict(hero=(shot_hero,2/1), ads=(shot_ads,16/9), phone=(shot_phone,16/9), remote=(shot_remote,16/9), logo=(shot_logo,1), banner=(shot_banner,16/9))

# ---- render ----
argv = sys.argv[sys.argv.index("--")+1:]
name, width, spp, out = argv[0], int(argv[1]), int(argv[2]), argv[3]
bpy.ops.wm.read_factory_settings(use_empty=True)
sc = bpy.context.scene
fn, aspect = SHOTS[name]; fn()
sc.world = bpy.data.worlds.new("w"); sc.world.use_nodes = True
sc.world.node_tree.nodes["Background"].inputs[0].default_value = (.002,.003,.006,1)
r = sc.render; r.engine = "CYCLES"; r.resolution_x = width; r.resolution_y = round(width/aspect); r.filepath = out
r.image_settings.file_format = "PNG"; r.image_settings.color_depth = "16"
sc.cycles.samples = spp; sc.cycles.use_denoising = True; sc.cycles.denoiser = "OPENIMAGEDENOISE"
sc.cycles.use_adaptive_sampling = True; sc.cycles.adaptive_threshold = .02
sc.cycles.max_bounces = 6; sc.cycles.caustics_reflective = False; sc.cycles.caustics_refractive = False
sc.cycles.sample_clamp_indirect = 8
sc.view_settings.view_transform = "Standard"   # so a screen shows its image's real colours
bpy.ops.render.render(write_still=True)
