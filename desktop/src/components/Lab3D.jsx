import { useEffect, useMemo, useRef, useState } from 'react';
import { Canvas, useFrame, useThree } from '@react-three/fiber';
import { OrbitControls } from '@react-three/drei';
import * as THREE from 'three';
import { ROW_D } from '../seating.js';
import InfoCard from './InfoCard.jsx';

const C = {
  floor: '#101418', empty: '#7d868e', desk: '#d3dade', locked: '#f0b429', stuck: '#ef5350',
  sel: '#4fd1dc', focus: '#ff2d95', chair: '#2f7fb5', chairFocus: '#7b2cbf', leg: '#5b636a',
};
const HOME = { cam: new THREE.Vector3(0, 34, 44), target: new THREE.Vector3(0, 0, 1) };

// Keeps showing the previous screenshot until the next one has decoded, so monitors never flash black.
function useJpegTexture(b64) {
  const [tex, setTex] = useState(null);
  const cur = useRef(null);
  useEffect(() => {
    if (!b64) return undefined;
    let live = true;
    const img = new Image();
    img.onload = () => {
      if (!live) return;
      const t = new THREE.Texture(img);
      t.colorSpace = THREE.SRGBColorSpace;
      t.needsUpdate = true;
      cur.current?.dispose();
      cur.current = t;
      setTex(t);
    };
    img.src = `data:image/jpeg;base64,${b64}`;
    return () => { live = false; };
  }, [b64]);
  useEffect(() => () => cur.current?.dispose(), []);
  return tex;
}

function Desk({ seat, pc, selected, focus, onClick, onDouble }) {
  const tex = useJpegTexture(pc?.thumb);
  const top = focus ? C.focus : selected ? C.sel : !pc ? C.empty : pc.status === 'locked' ? C.locked : pc.status === 'stuck' ? C.stuck : C.desk;
  const glow = focus || selected ? 0.55 : 0;
  const chair = focus ? C.chairFocus : C.chair;
  const handlers = pc ? {
    onClick: (e) => { if (e.delta > 6) return; e.stopPropagation(); onClick(pc.id, e.nativeEvent); },
    onDoubleClick: (e) => { e.stopPropagation(); onDouble(pc.id); },
  } : {};
  return (
    <group position={[seat.x, 0, seat.z]} rotation={[0, seat.rot, 0]}>
      <mesh position={[0, 0.75, 0]} castShadow receiveShadow {...handlers}>
        <boxGeometry args={[1.25, 0.06, 0.72]} />
        <meshStandardMaterial color={top} emissive={top} emissiveIntensity={glow} roughness={0.6} />
      </mesh>
      {[[-0.56, -0.3], [0.56, -0.3], [-0.56, 0.3], [0.56, 0.3]].map(([x, z]) => (
        <mesh key={`${x}${z}`} position={[x, 0.37, z]}><boxGeometry args={[0.04, 0.74, 0.04]} /><meshStandardMaterial color={C.leg} /></mesh>
      ))}
      {pc && (
        <group position={[0, 1.05, -0.22]} {...handlers}>
          <mesh castShadow><boxGeometry args={[0.72, 0.46, 0.04]} /><meshStandardMaterial color="#15191d" /></mesh>
          <mesh position={[0, 0, 0.03]}>
            <planeGeometry args={[0.66, 0.4]} />
            {/* key: three.js must rebuild the shader when a material first gets a map */}
            {tex ? <meshBasicMaterial key="tex" map={tex} toneMapped={false} /> : <meshBasicMaterial key="plain" color={pc.locked ? '#2a2108' : '#0f2a30'} />}
          </mesh>
          <mesh position={[0, -0.26, -0.02]}><boxGeometry args={[0.08, 0.1, 0.08]} /><meshStandardMaterial color="#15191d" /></mesh>
        </group>
      )}
      <mesh position={[0, 0.45, 0.62]} castShadow><boxGeometry args={[0.5, 0.07, 0.46]} /><meshStandardMaterial color={chair} /></mesh>
      <mesh position={[0, 0.72, 0.86]} rotation={[-0.12, 0, 0]} castShadow><boxGeometry args={[0.5, 0.5, 0.06]} /><meshStandardMaterial color={chair} /></mesh>
    </group>
  );
}

function titleTexture(sub) {
  const c = document.createElement('canvas');
  c.width = 2048; c.height = 560;
  const g = c.getContext('2d');
  const bg = g.createLinearGradient(0, 0, 0, 560);
  bg.addColorStop(0, '#0d2328'); bg.addColorStop(1, '#0b1114');
  g.fillStyle = bg; g.fillRect(0, 0, 2048, 560);
  g.textAlign = 'center';
  g.shadowColor = '#4fb3bf'; g.shadowBlur = 50;
  g.fillStyle = '#6fd6e0';
  g.font = '800 220px Inter, "Segoe UI", system-ui, sans-serif';
  g.fillText('CLASS CONTROL', 1024, 320);
  g.shadowBlur = 0;
  g.fillStyle = '#8fb2b8';
  g.font = '500 50px ui-monospace, Menlo, Consolas, monospace';
  g.fillText(sub.split('').join(' '), 1024, 450);
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  return t;
}

function Stage({ sharing }) {
  const tex = useMemo(() => titleTexture(sharing ? '● SHARING YOUR SCREEN' : 'INSTRUCTOR'), [sharing]);
  useEffect(() => () => tex.dispose(), [tex]);
  return (
    <group position={[0, 0, -7]}>
      <mesh position={[0, 0.25, 0]} receiveShadow castShadow><boxGeometry args={[44, 0.5, 7]} /><meshStandardMaterial color="#1b2025" /></mesh>
      <mesh position={[0, 4.6, -1.5]} castShadow><boxGeometry args={[23, 6.6, 0.3]} /><meshStandardMaterial color="#0c1013" emissive="#4fb3bf" emissiveIntensity={0.08} /></mesh>
      <mesh position={[0, 4.6, -1.34]}><planeGeometry args={[22.2, 6.07]} /><meshBasicMaterial map={tex} toneMapped={false} /></mesh>
      <mesh position={[0, 0.9, 1.6]} castShadow><boxGeometry args={[3.2, 0.9, 1.2]} /><meshStandardMaterial color="#2a3138" /></mesh>
    </group>
  );
}

// Pins DOM overlay elements (labels, block tags, info card) to 3D points every frame.
// Replaces drei <Html>, which crashes on unmount under React 19.
function Projector({ items }) {
  const v = useMemo(() => new THREE.Vector3(), []);
  useFrame(({ camera, size }) => {
    for (const { el, pos, label } of items.current.values()) {
      // Name labels only when zoomed in (or highlighted), so the overview stays clean.
      const far = label === 'auto' && camera.position.distanceTo(v.set(pos[0], pos[1], pos[2])) > 24;
      v.set(pos[0], pos[1], pos[2]).project(camera);
      const hidden = far || v.z > 1 || Math.abs(v.x) > 1.2 || Math.abs(v.y) > 1.2;
      el.style.display = hidden ? 'none' : '';
      if (!hidden) el.style.transform = `translate(${((v.x + 1) / 2) * size.width}px, ${((1 - v.y) / 2) * size.height}px)`;
    }
  });
  return null;
}

// Smoothly flies the camera to a seat (search pick) or back home.
function CameraRig({ goal }) {
  const controls = useThree((s) => s.controls);
  const camera = useThree((s) => s.camera);
  const flying = useRef(false);
  useEffect(() => { flying.current = true; }, [goal]);
  useFrame((_, dt) => {
    if (!flying.current || !controls) return;
    const k = 1 - Math.pow(0.002, dt);
    controls.target.lerp(goal.target, k);
    camera.position.lerp(goal.cam, k);
    controls.update();
    if (camera.position.distanceTo(goal.cam) < 0.05) flying.current = false;
  });
  return null;
}

export default function Lab3D({ layout, seats, bySeat, seatOfPc, selected, focusId, flyTo, sharing, onSeatClick, onSeatDouble, onBlockClick, onEmpty, onRemote, onAction, onCloseInfo }) {
  const wrap = useRef(null);
  const ring = useRef(null);
  const tag = useRef(null);
  const down = useRef(null);
  const items = useRef(new Map());
  const pin = (key, pos, label) => (el) => { if (el) items.current.set(key, { el, pos, label }); else items.current.delete(key); };

  const goal = useMemo(() => {
    const s = flyTo && seats.find((x) => x.key === flyTo.key);
    if (!s) return { ...HOME, n: flyTo?.n };
    const p = new THREE.Vector3(s.x, 0.8, s.z);
    return { target: p, cam: p.clone().add(new THREE.Vector3(0, 9, 12)), n: flyTo.n };
  }, [flyTo, seats]);

  const focusSeat = focusId && seats.find((s) => s.key === seatOfPc.get(focusId));
  const focusPc = focusSeat && bySeat.get(focusSeat.key);

  function move(e) {
    const r = wrap.current.getBoundingClientRect();
    const x = Math.round(e.clientX - r.left), y = Math.round(e.clientY - r.top);
    ring.current.style.display = 'block';
    ring.current.style.transform = `translate(${x}px, ${y}px)`;
    tag.current.textContent = `X: ${x} / Y: ${y}`;
  }

  return (
    <div className="canvas-wrap" ref={wrap} onPointerMove={move} onPointerLeave={() => { ring.current.style.display = 'none'; }}
      onPointerDown={(e) => { down.current = [e.clientX, e.clientY]; }}>
      <Canvas shadows dpr={[1, 2]} camera={{ position: HOME.cam.toArray(), fov: 45, near: 0.5, far: 400 }}
        onPointerMissed={(e) => { const d = down.current; if (d && Math.hypot(e.clientX - d[0], e.clientY - d[1]) < 6) onEmpty(); }}>
        <color attach="background" args={['#0f1215']} />
        <fog attach="fog" args={['#0f1215', 55, 120]} />
        <ambientLight intensity={0.55} />
        <hemisphereLight args={['#bfe9ef', '#0b0e10', 0.35]} />
        <directionalLight position={[14, 30, 18]} intensity={1.4} castShadow shadow-mapSize={[2048, 2048]}
          shadow-camera-left={-40} shadow-camera-right={40} shadow-camera-top={40} shadow-camera-bottom={-40} />
        <mesh rotation={[-Math.PI / 2, 0, 0]} receiveShadow onPointerDown={() => {}}>
          <planeGeometry args={[200, 200]} />
          <meshStandardMaterial color={C.floor} />
        </mesh>
        <Stage sharing={sharing} />
        {seats.map((s) => {
          const pc = bySeat.get(s.key);
          return <Desk key={s.key} seat={s} pc={pc} selected={!!pc && selected.has(pc.id)} focus={!!pc && pc.id === focusId} onClick={onSeatClick} onDouble={onSeatDouble} />;
        })}
        <OrbitControls makeDefault target={HOME.target.toArray()} enableDamping maxPolarAngle={1.3} minDistance={5} maxDistance={90} />
        <CameraRig goal={goal} />
        <Projector items={items} />
      </Canvas>
      <div className="overlay">
        {seats.map((s) => {
          const pc = bySeat.get(s.key);
          if (!pc) return null;
          const cls = pc.id === focusId ? 'focus' : selected.has(pc.id) ? 'sel' : '';
          return <div key={s.key} className="pin" ref={pin(`l${s.key}`, [s.x, 1.42, s.z - 0.22], cls ? 'always' : 'auto')}><div className={`desk-label ${cls}`}>{pc.host}</div></div>;
        })}
        {layout.blocks.map((b, bi) => (
          <div key={`b${bi}`} className="pin" ref={pin(`b${bi}`, [b.x, 0.1, b.z - ROW_D * 0.8])}>
            <button className="block-tag" onClick={(e) => onBlockClick(bi, e.shiftKey || e.ctrlKey || e.metaKey)} title={`Select all PCs in block ${b.name}`}>BLOCK {b.name}</button>
          </div>
        ))}
        {focusPc && (
          <div className="pin top" ref={pin('info', [focusSeat.x + 0.7, 0.8, focusSeat.z])}>
            <div className="info-anchor">
              <div className="stem" />
              <InfoCard pc={focusPc} seat={focusSeat} onRemote={() => onRemote(focusPc.id)} onAction={(cmd) => onAction(focusPc.id, cmd)} onClose={onCloseInfo} />
            </div>
          </div>
        )}
      </div>
      <div className="cursor-ring" ref={ring}><div className="ring" /><div className="tag" ref={tag} /></div>
    </div>
  );
}
