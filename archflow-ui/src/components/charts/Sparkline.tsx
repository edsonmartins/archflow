interface SparklineProps {
    values: number[];
    /** Coordinate-space width (the SVG scales to its container via viewBox). */
    width?: number;
    height?: number;
    color?: string;
    strokeWidth?: number;
    /** Optional context for the accessible label, e.g. the metric name. */
    label?: string;
}

const fmt = (n: number) =>
    Number.isInteger(n) ? String(n) : n.toFixed(2);

/**
 * Minimal SVG sparkline for the overview cards. No chart-library dependency.
 *
 * Renders responsively (fills its container width via viewBox), uses theme
 * tokens so it adapts to dark mode, and exposes a meaningful aria-label
 * (last / min / max) so screen-reader users get the trend without seeing it.
 * For a full axed/tooltipped chart use @mantine/charts LineChart instead.
 */
export default function Sparkline({
    values,
    width = 160,
    height = 36,
    color = 'var(--mantine-color-blue-6)',
    strokeWidth = 1.6,
    label,
}: SparklineProps) {
    const svgStyle = { width: '100%', height, display: 'block' } as const;

    if (values.length === 0) {
        return (
            <svg
                viewBox={`0 0 ${width} ${height}`}
                preserveAspectRatio="none"
                style={svgStyle}
                role="img"
                aria-label={label ? `${label}: no data` : 'No data'}
            >
                <line
                    x1={0}
                    y1={height / 2}
                    x2={width}
                    y2={height / 2}
                    stroke="var(--mantine-color-gray-4)"
                    strokeWidth={1}
                    strokeDasharray="3,3"
                />
            </svg>
        );
    }

    const min = Math.min(...values);
    const max = Math.max(...values);
    const last = values[values.length - 1];
    const range = max - min || 1;
    const padX = 2;
    const padY = 4;
    const innerW = width - padX * 2;
    const innerH = height - padY * 2;

    const ariaLabel = `${label ? `${label}: ` : ''}last ${fmt(last)}, min ${fmt(min)}, max ${fmt(max)}`;

    if (values.length === 1) {
        return (
            <svg viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none" style={svgStyle} role="img" aria-label={ariaLabel}>
                <circle cx={width / 2} cy={height / 2} r={2.5} fill={color} />
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
        <svg viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none" style={svgStyle} role="img" aria-label={ariaLabel}>
            <path d={areaPath} fill={color} fillOpacity={0.13} />
            <path d={linePath} fill="none" stroke={color} strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" />
        </svg>
    );
}
