import { useState, useCallback } from 'react';
import {
    Stack, Text, TextInput, NumberInput, Select, Checkbox, Textarea,
    PasswordInput, FileInput, Button, Group,
} from '@mantine/core';
import { DateInput } from '@mantine/dates';
import type { FormDataType, FormField } from '../../services/conversation-api';

interface FormRendererProps {
    formData: FormDataType;
    onSubmit: (data: Record<string, unknown>) => void;
    onCancel?: () => void;
    loading?: boolean;
}

export default function FormRenderer({ formData, onSubmit, onCancel, loading }: FormRendererProps) {
    const [values, setValues] = useState<Record<string, unknown>>(() => {
        const initial: Record<string, unknown> = {};
        for (const field of formData.fields) {
            if (field.type === 'CHECKBOX') {
                initial[field.id] = field.defaultValue === 'true';
            } else {
                initial[field.id] = field.defaultValue ?? '';
            }
        }
        return initial;
    });

    const [errors, setErrors] = useState<Record<string, string>>({});

    const setValue = useCallback((id: string, value: unknown) => {
        setValues((prev) => ({ ...prev, [id]: value }));
        setErrors((prev) => {
            if (prev[id]) {
                const next = { ...prev };
                delete next[id];
                return next;
            }
            return prev;
        });
    }, []);

    const handleSubmit = useCallback(() => {
        const newErrors: Record<string, string> = {};
        for (const field of formData.fields) {
            if (field.required) {
                const val = values[field.id];
                if (val === undefined || val === null || val === '') {
                    newErrors[field.id] = `${field.label} is required`;
                }
            }
        }
        if (Object.keys(newErrors).length > 0) {
            setErrors(newErrors);
            return;
        }
        onSubmit(values);
    }, [formData.fields, values, onSubmit]);

    return (
        <Stack gap="sm">
            {formData.title && (
                <Text fw={600} size="sm">{formData.title}</Text>
            )}
            {formData.description && (
                <Text size="xs" c="dimmed">{formData.description}</Text>
            )}

            {formData.fields.map((field) => (
                <FormFieldInput
                    key={field.id}
                    field={field}
                    value={values[field.id]}
                    error={errors[field.id]}
                    onChange={(val) => setValue(field.id, val)}
                />
            ))}

            <Group justify="flex-end" mt="xs">
                {onCancel && (
                    <Button variant="default" size="xs" onClick={onCancel} disabled={loading}>
                        {formData.cancelLabel || 'Cancel'}
                    </Button>
                )}
                <Button size="xs" onClick={handleSubmit} loading={loading}>
                    {formData.submitLabel || 'Submit'}
                </Button>
            </Group>
        </Stack>
    );
}

function FormFieldInput({
    field, value, error, onChange,
}: {
    field: FormField;
    value: unknown;
    error?: string;
    onChange: (value: unknown) => void;
}) {
    const common = {
        label: field.label,
        description: field.description,
        placeholder: field.placeholder,
        required: field.required,
        error,
        size: 'xs' as const,
    };

    switch (field.type) {
        case 'TEXT':
        case 'PHONE':
        case 'URL':
            return (
                <TextInput
                    {...common}
                    type={field.type === 'URL' ? 'url' : field.type === 'PHONE' ? 'tel' : 'text'}
                    value={(value as string) ?? ''}
                    onChange={(e) => onChange(e.currentTarget.value)}
                />
            );

        case 'EMAIL':
            return (
                <TextInput
                    {...common}
                    type="email"
                    value={(value as string) ?? ''}
                    onChange={(e) => onChange(e.currentTarget.value)}
                />
            );

        case 'NUMBER':
            return (
                <NumberInput
                    {...common}
                    value={(value as number) ?? ''}
                    onChange={(val) => onChange(val)}
                />
            );

        case 'SELECT':
            return (
                <Select
                    {...common}
                    data={field.options || []}
                    value={(value as string) ?? null}
                    onChange={(val) => onChange(val)}
                />
            );

        case 'CHECKBOX':
            return (
                <Checkbox
                    label={field.label}
                    description={field.description}
                    size="xs"
                    checked={(value as boolean) ?? false}
                    onChange={(e) => onChange(e.currentTarget.checked)}
                    error={error}
                />
            );

        case 'TEXTAREA':
            return (
                <Textarea
                    {...common}
                    autosize
                    minRows={3}
                    maxRows={8}
                    value={(value as string) ?? ''}
                    onChange={(e) => onChange(e.currentTarget.value)}
                />
            );

        case 'FILE':
            return (
                <FileInput
                    {...common}
                    value={(value as File) ?? null}
                    onChange={(file) => onChange(file)}
                />
            );

        case 'DATE':
            return (
                <DateInput
                    {...common}
                    value={value ? new Date(value as string) : null}
                    onChange={(date) => onChange(date?.toISOString() ?? '')}
                />
            );

        case 'PASSWORD':
            return (
                <PasswordInput
                    {...common}
                    value={(value as string) ?? ''}
                    onChange={(e) => onChange(e.currentTarget.value)}
                />
            );

        case 'HIDDEN':
            return null;

        default:
            return (
                <TextInput
                    {...common}
                    value={(value as string) ?? ''}
                    onChange={(e) => onChange(e.currentTarget.value)}
                />
            );
    }
}
