import {
    IconApi,
    IconBooks,
    IconBrain,
    IconBrandWhatsapp,
    IconBuildingFactory2,
    IconEye,
    IconMessageChatbot,
    IconPhoto,
    IconPlayerPlay,
    IconPlugConnected,
    IconRepeat,
    IconUserCheck,
} from '@tabler/icons-react';
import { useReducedMotion } from '@mantine/hooks';
import { useTranslation } from 'react-i18next';
import type { ComponentType } from 'react';
import brandMark from '../assets/brand/mark.png';
import './LoginArchitecture.css';

/**
 * Ilustração animada do painel da marca no login: a arquitetura do archflow em
 * miniatura. Os canais de entrada (esquerda) mandam pedidos ao agente, que roda o
 * ciclo pensar → agir → observar e aciona as ferramentas e sistemas (direita);
 * embaixo, os provedores de LLM via LangChain4j, consultados a cada volta. Cada
 * pedido entra na cor do canal e sai na cor da ferramenta.
 *
 * Tudo é SVG: os pacotes andam com `animateMotion` (SMIL) e o resto é CSS. Quem
 * pede menos movimento (`prefers-reduced-motion`) vê o diagrama parado, sem os
 * pacotes. É decorativo (`aria-hidden`): o conteúdo está no título e nos
 * destaques ao lado.
 */

type Icon = ComponentType<{ size?: number; stroke?: number; x?: number; y?: number; color?: string }>;

interface ArchNode {
    label: string;
    examples: string;
    icon: Icon;
    color: string;
}

// Geometria (viewBox 860 × 316). A fonte do archflow é monoespaçada (mais larga),
// por isso os cartões têm folga para o rótulo mais longo.
const WIDTH = 860;
const HEIGHT = 316;
const NODE_W = 200;
const NODE_H = 42;
const NODE_Y = [8, 94, 180, 266];
const HUB = { x: 318, y: 84, w: 224, h: 148 };
const PORTS = [128, 148, 168, 188];
const DEST_X = WIDTH - NODE_W;
const LLM = { x: 255, y: 276, w: 350, h: 32 };

/** Para qual ferramenta vai o pedido de cada canal (cruzado de propósito: o agente decide). */
const ROUTE = [1, 3, 0, 2];

// Ritmo: um pedido sai de um canal a cada STEP; o ciclo todo dura CYCLE.
const CYCLE = 6;
const STEP = CYCLE / 4;
const TRAVEL = 1.5;
const THINK = 0.6;

const center = (i: number) => NODE_Y[i] + NODE_H / 2;
const inPath = (i: number) =>
    `M${NODE_W},${center(i)} C${NODE_W + 62},${center(i)} ${HUB.x - 62},${PORTS[i]} ${HUB.x},${PORTS[i]}`;
const outPath = (port: number, j: number) => {
    const x0 = HUB.x + HUB.w;
    return `M${x0},${PORTS[port]} C${x0 + 62},${PORTS[port]} ${DEST_X - 62},${center(j)} ${DEST_X},${center(j)}`;
};

function Packet({ path, color, begin }: { path: string; color: string; begin: number }) {
    const frac = TRAVEL / CYCLE;
    const common = { dur: `${CYCLE}s`, begin: `${begin}s`, repeatCount: 'indefinite' as const };
    return (
        <g opacity={0}>
            <circle r={9} fill={color} opacity={0.22} />
            <circle r={4.2} fill={color} />
            <circle r={1.8} fill="#fff" />
            <animateMotion
                {...common}
                path={path}
                calcMode="spline"
                keyPoints="0;1;1"
                keyTimes={`0;${frac};1`}
                keySplines="0.45 0 0.55 1;0 0 1 1"
            />
            <animate
                {...common}
                attributeName="opacity"
                values="0;1;1;0;0"
                keyTimes={`0;${frac * 0.12};${frac * 0.9};${frac};1`}
            />
        </g>
    );
}

function NodeCard({ node, x, y, delay, t }: { node: ArchNode; x: number; y: number; delay: number; t: (k: string) => string }) {
    const NodeIcon = node.icon;
    return (
        <g transform={`translate(${x} ${y})`}>
            <rect className="af-arch-card" width={NODE_W} height={NODE_H} rx={12} />
            <rect
                className="af-arch-pulse"
                width={NODE_W}
                height={NODE_H}
                rx={12}
                stroke={node.color}
                style={{ animationDelay: `${delay}s` }}
            />
            <rect x={7} y={7} width={28} height={28} rx={8} fill={node.color} opacity={0.2} />
            <rect x={7} y={7} width={28} height={28} rx={8} fill="none" stroke={node.color} strokeOpacity={0.55} />
            <NodeIcon x={12} y={12} size={18} stroke={1.8} color={node.color} />
            <text className="af-arch-label" x={44} y={15}>{t(node.label)}</text>
            <text className="af-arch-examples" x={44} y={29.5}>{t(node.examples)}</text>
        </g>
    );
}

export default function LoginArchitecture() {
    const { t } = useTranslation();
    const still = useReducedMotion();

    const inputs: ArchNode[] = [
        { label: 'login.arch.chat', examples: 'login.arch.chatEx', icon: IconMessageChatbot, color: '#60A5FA' },
        { label: 'login.arch.whatsapp', examples: 'login.arch.whatsappEx', icon: IconBrandWhatsapp, color: '#4ADE80' },
        { label: 'login.arch.apis', examples: 'login.arch.apisEx', icon: IconApi, color: '#FACC15' },
        { label: 'login.arch.docs', examples: 'login.arch.docsEx', icon: IconPhoto, color: '#FB7185' },
    ];
    const outputs: ArchNode[] = [
        { label: 'login.arch.mcp', examples: 'login.arch.mcpEx', icon: IconPlugConnected, color: '#2DD4BF' },
        { label: 'login.arch.rag', examples: 'login.arch.ragEx', icon: IconBooks, color: '#A78BFA' },
        { label: 'login.arch.systems', examples: 'login.arch.systemsEx', icon: IconBuildingFactory2, color: '#38BDF8' },
        { label: 'login.arch.approval', examples: 'login.arch.approvalEx', icon: IconUserCheck, color: '#FBBF24' },
    ];
    const stages: { label: string; icon: Icon }[] = [
        { label: 'login.arch.think', icon: IconBrain },
        { label: 'login.arch.act', icon: IconPlayerPlay },
        { label: 'login.arch.observe', icon: IconEye },
    ];

    const stageW = 62;
    const stageGap = 7;
    const stageX0 = HUB.x + (HUB.w - (stageW * 3 + stageGap * 2)) / 2;
    const stageY = HUB.y + 52;
    const hubMid = HUB.x + HUB.w / 2;
    const llmLink = `M${hubMid},${HUB.y + HUB.h} V${LLM.y}`;

    return (
        <svg
            className="af-arch"
            data-still={still || undefined}
            viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
            preserveAspectRatio="xMidYMid meet"
            aria-hidden
            focusable={false}
        >
            <defs>
                <linearGradient id="af-arch-hub" x1="0" y1="0" x2="1" y2="1">
                    <stop offset="0" stopColor="#3B82F6" stopOpacity="0.34" />
                    <stop offset="1" stopColor="#7C3AED" stopOpacity="0.22" />
                </linearGradient>
                <linearGradient id="af-arch-light" x1="0" y1="0" x2="1" y2="0">
                    <stop offset="0" stopColor="#60A5FA" />
                    <stop offset="0.5" stopColor="#2DD4BF" />
                    <stop offset="1" stopColor="#FACC15" />
                </linearGradient>
                <radialGradient id="af-arch-aura">
                    <stop offset="0" stopColor="#3B82F6" stopOpacity="0.5" />
                    <stop offset="1" stopColor="#3B82F6" stopOpacity="0" />
                </radialGradient>
            </defs>

            {/* Ligações: traço base + pontilhado que corre na cor do nó. */}
            {inputs.map((node, i) => (
                <g key={`in${i}`}>
                    <path className="af-arch-track" d={inPath(i)} />
                    <path className="af-arch-current" d={inPath(i)} stroke={node.color} />
                </g>
            ))}
            {inputs.map((_, i) => {
                const j = ROUTE[i];
                return (
                    <g key={`out${i}`}>
                        <path className="af-arch-track" d={outPath(i, j)} />
                        <path className="af-arch-current" d={outPath(i, j)} stroke={outputs[j].color} />
                    </g>
                );
            })}
            <path className="af-arch-llm-link" d={llmLink} />

            {/* Agente. */}
            <ellipse
                className="af-arch-aura"
                cx={hubMid}
                cy={HUB.y + HUB.h / 2}
                rx={HUB.w * 0.72}
                ry={HUB.h * 0.9}
                fill="url(#af-arch-aura)"
            />
            <rect className="af-arch-hub" x={HUB.x} y={HUB.y} width={HUB.w} height={HUB.h} rx={18} />
            <rect className="af-arch-hub-light" x={HUB.x} y={HUB.y} width={HUB.w} height={HUB.h} rx={18} pathLength={100} />
            <rect
                className="af-arch-hub-light"
                x={HUB.x}
                y={HUB.y}
                width={HUB.w}
                height={HUB.h}
                rx={18}
                pathLength={100}
                style={{ animationDelay: '-3s' }}
            />
            <image href={brandMark} x={HUB.x + 18} y={HUB.y + 14} width={24} height={24} />
            <text className="af-arch-hub-title" x={HUB.x + 50} y={HUB.y + 27}>{t('login.arch.agent')}</text>
            <IconRepeat x={HUB.x + HUB.w - 30} y={HUB.y + 18} size={16} stroke={1.8} color="#7DD3FC" />

            {stages.map((stage, k) => {
                const x = stageX0 + k * (stageW + stageGap);
                const StageIcon = stage.icon;
                return (
                    <g key={stage.label} transform={`translate(${x} ${stageY})`}>
                        <rect className="af-arch-stage" width={stageW} height={58} rx={11} />
                        <rect
                            className="af-arch-stage-light"
                            width={stageW}
                            height={58}
                            rx={11}
                            style={{ animationDelay: `${(k * THINK) / 3}s` }}
                        />
                        <StageIcon x={stageW / 2 - 10} y={10} size={20} stroke={1.7} color="#fff" />
                        <text className="af-arch-stage-label" x={stageW / 2} y={45}>{t(stage.label)}</text>
                        {k < stages.length - 1 && (
                            <path className="af-arch-stage-link" d={`M${stageW},29 h${stageGap}`} />
                        )}
                    </g>
                );
            })}
            {/* Volta do ciclo: observar → pensar, por baixo das etapas. */}
            <path
                className="af-arch-loop"
                d={`M${stageX0 + stageW * 2.5 + stageGap * 2},${stageY + 58} v6 H${stageX0 + stageW / 2} v-6`}
            />
            {PORTS.map((y) => (
                <g key={y}>
                    <circle className="af-arch-port" cx={HUB.x} cy={y} r={3} />
                    <circle className="af-arch-port" cx={HUB.x + HUB.w} cy={y} r={3} />
                </g>
            ))}

            {/* Provedores de LLM. */}
            <g transform={`translate(${LLM.x} ${LLM.y})`}>
                <rect className="af-arch-llm" width={LLM.w} height={LLM.h} rx={16} />
                <circle className="af-arch-llm-led" cx={17} cy={LLM.h / 2} r={4} />
                <text className="af-arch-llm-label" x={LLM.w / 2 + 8} y={LLM.h / 2}>{t('login.arch.llms')}</text>
            </g>

            {inputs.map((node, i) => (
                <NodeCard key={node.label} node={node} x={0} y={NODE_Y[i]} delay={i * STEP} t={t} />
            ))}
            {outputs.map((node, j) => (
                <NodeCard
                    key={node.label}
                    node={node}
                    x={DEST_X}
                    y={NODE_Y[j]}
                    delay={(ROUTE.indexOf(j) * STEP + TRAVEL * 2 + THINK) % CYCLE}
                    t={t}
                />
            ))}

            {/* Pacotes: entram na cor do canal, saem na cor da ferramenta. */}
            {!still && inputs.map((node, i) => {
                const j = ROUTE[i];
                return (
                    <g key={`p${i}`}>
                        <Packet path={inPath(i)} color={node.color} begin={i * STEP} />
                        <Packet path={outPath(i, j)} color={outputs[j].color} begin={i * STEP + TRAVEL + THINK} />
                    </g>
                );
            })}
            {/* Consulta ao LLM: vai e volta entre o agente e os provedores. */}
            {!still && (
                <circle r={2.6} fill="#7DD3FC">
                    <animateMotion
                        dur="2.4s"
                        repeatCount="indefinite"
                        keyPoints="0;1;0"
                        keyTimes="0;0.5;1"
                        calcMode="linear"
                        path={llmLink}
                    />
                </circle>
            )}
        </svg>
    );
}
