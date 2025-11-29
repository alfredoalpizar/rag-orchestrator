import MDEditor from '@uiw/react-md-editor';
import * as commands from '@uiw/react-md-editor/commands';
import '@uiw/react-md-editor/markdown-editor.css';
import '@uiw/react-markdown-preview/markdown.css';
import './markdown-editor.css';

interface MarkdownEditorProps {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  height?: number;
  error?: string;
  preview?: 'edit' | 'preview' | 'live';
}

export default function MarkdownEditor({
  value,
  onChange,
  placeholder = 'Enter markdown content...',
  height = 400,
  error,
  preview = 'live'
}: MarkdownEditorProps) {
  const handleChange = (val?: string) => {
    onChange(val || '');
  };

  return (
    <div className="markdown-editor-wrapper">
      <MDEditor
        value={value}
        onChange={handleChange}
        preview={preview}
        height={height}
        data-color-mode="light"
        textareaProps={{
          placeholder,
          style: {
            fontSize: 14,
            lineHeight: 1.5,
            fontFamily: 'ui-monospace, SFMono-Regular, "SF Mono", Monaco, Consolas, "Liberation Mono", "Courier New", monospace'
          }
        }}
        commands={[
          commands.bold,
          commands.italic,
          commands.divider,
          commands.title,
          commands.title2,
          commands.title3,
          commands.divider,
          commands.unorderedListCommand,
          commands.orderedListCommand,
          commands.divider,
          commands.link,
          commands.quote,
          commands.code,
          commands.divider,
          commands.help,
        ]}
      />
      {error && (
        <p className="mt-1 text-sm text-red-600">{error}</p>
      )}

    </div>
  );
}