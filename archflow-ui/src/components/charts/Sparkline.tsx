interface SparklineProps {
    values: number[];
    width?: number;
    height?: number;
    color?: string;
    strokeWidth?: number;
}

/**
 * Minimal SVG sparkline. No external chart library dependency — we only
 * need to visualize a ~12-point latency series on the overview cards.
 *
 * Handles edge cases: empty arrays render a flat baseline, a single
 * point renders as a dot.
 */
export default function Sparkline({
    values,
    width = 160,
    height = 36,
    color = '#2563EB',
    strokeWidth = 1.6,
}: SparklineProps) {
    if (values.length === 0) {
        return (
            <svg width={width} height={height} role="img" aria-label="empty sparkline">
                <line
                    x1={0}
                    y1={height / 2}
                    x2={width}
                    y2={height / 2}
                    stroke="#E5E7EB"
                    strokeWidth={1}
                    strokeDasharray="3,3"
                />
            </svg>
        );
    }

    const min = Math.min(...values);
    const max = Math.max(...values);
    const range = max - min || 1;
    const padX = 2;
    const padY = 4;
    const innerW = width - padX * 2;
    const innerH = height - padY * 2;

    if (values.length === 1) {
        return (
            <svg width={width} height={height} role="img" aria-label="sparkline">
                <circle
                    cx={width / 2}
                    cy={height / 2}
                    r={2.5}
                    fill={color}
                />
            </svg>
        );
    }

    const points = values.map((v, i) => {
        const x = padX + (i / (values.length - 1)) * innerW;
        const y = padY + innerH - ((v - min) / range) * innerH;
        return [x, y] as const;
    });

    const linePath = points
        .map(([x, y], i) => `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`)
        .join(' ');
    const areaPath = `${linePath} L${points[points.length - 1][0]},${height} L${points[0][0]},${height} Z`;

    return (
        <svg width={width} height={height} role="img" aria-label="sparkline">
            <path d={areaPath} fill={`${color}22`} />
            <path d={linePath} fill="none" stroke={color} strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" />
        </svg>
    );
}
