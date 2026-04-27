import sys

def check_syntax(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        text = f.read()

    i = 0
    clean_text = ''
    in_string = False
    in_char = False
    in_block_comment = False
    in_line_comment = False
    
    while i < len(text):
        if text[i] == '\n':
            if in_line_comment:
                in_line_comment = False
            clean_text += '\n'
            i += 1
            continue
            
        if in_line_comment:
            i += 1
            continue
            
        if in_block_comment:
            if text[i:i+2] == '*/':
                in_block_comment = False
                i += 2
            else:
                i += 1
            continue
            
        if in_string:
            if text[i] == '\\':
                i += 2
                continue
            if text[i] == '"':
                in_string = False
            i += 1
            continue
            
        if in_char:
            if text[i] == '\\':
                i += 2
                continue
            if text[i] == "'":
                in_char = False
            i += 1
            continue
            
        if text[i:i+2] == '//':
            in_line_comment = True
            i += 2
            continue
            
        if text[i:i+2] == '/*':
            in_block_comment = True
            i += 2
            continue
            
        if text[i] == '"':
            in_string = True
            i += 1
            continue
            
        if text[i] == "'":
            in_char = True
            i += 1
            continue
            
        clean_text += text[i]
        i += 1

    stack = []
    lines = clean_text.split('\n')
    for idx, line in enumerate(lines):
        for char in line:
            if char in '{[(':
                stack.append((char, idx+1))
            elif char in '}])':
                if not stack:
                    print(f'Extra {char} at line {idx+1}')
                    return
                top, _ = stack.pop()
                if (top == '{' and char != '}') or \
                   (top == '[' and char != ']') or \
                   (top == '(' and char != ')'):
                    print(f'Mismatch: {top} and {char} at line {idx+1}')
                    return

    if stack:
        print('Unclosed pairs:')
        for char, l in stack:
            print(f'{char} at line {l}')
    else:
        print('Syntax is perfectly balanced.')

check_syntax('app/src/main/java/com/github/ma1co/pmcademo/app/DiptychManager.java')
