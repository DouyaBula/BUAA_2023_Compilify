package Parser;

import Lexer.Symbol;
import Lexer.Token;

import java.util.ArrayList;

public class Stepper {
    private final ArrayList<Token> tokens;
    private int index;

    private static Stepper stepper;
    public static Stepper getInstance(ArrayList<Token> tokens) {
        if (stepper == null) {
            stepper = new Stepper(tokens);
        }
        return stepper;
    }

    private Stepper(ArrayList<Token> tokens) {
        this.tokens = tokens;
        this.index = 0;
    }

    public int getLine() {
        int line = peek().getLine().intValue();
        return line;
    }

    public boolean atEnd() {
        return index >= tokens.size();
    }

    public Token peek() {
        return peek(0);
    }

    public Token peek(int offset) {
        if (index + offset >= tokens.size()) {
            return Token.nullToken;
        }
        return tokens.get(index + offset);
    }

    public Token next() {
        index++;
        if (index >= tokens.size()) {
            index = tokens.size();
            return Token.nullToken;
        } else {
            return tokens.get(index - 1);
        }
    }

    public Token next(int offset) {
        index += offset;
        if (index >= tokens.size()) {
            index = tokens.size();
            return Token.nullToken;
        } else {
            return tokens.get(index - offset);
        }
    }

    public boolean is(Symbol... tokens) {
        for (int i = 0; i < tokens.length; i++) {
            if (peek(i) == Token.nullToken || !peek(i).is(tokens[i])) {
                return false;
            }
        }
        return true;
    }

    public boolean isUnaryExp() {
        return peek().is(Symbol.LPARENT)
                || peek().is(Symbol.IDENFR)
                || peek().is(Symbol.INTCON)
                || peek().is(Symbol.PLUS)
                || peek().is(Symbol.MINU)
                || peek().is(Symbol.NOT);
    }

    public boolean isStmt() {
        return peek().is(Symbol.IDENFR)
                || isUnaryExp()
                || peek().is(Symbol.LBRACE)
                || peek().is(Symbol.IFTK)
                || peek().is(Symbol.FORTK)
                || peek().is(Symbol.BREAKTK)
                || peek().is(Symbol.CONTINUETK)
                || peek().is(Symbol.RETURNTK)
                || peek().is(Symbol.PRINTFTK)
                || peek().is(Symbol.SEMICN);
    }

    public boolean isGetintStmt() {
        if (!peek().is(Symbol.IDENFR)) {
            return false;
        }
        int pos = 1;
        while (!peek(pos).is(Symbol.SEMICN) && pos < tokens.size()) {
            if (peek(pos).is(Symbol.ASSIGN)) {
                return peek(pos + 1).is(Symbol.GETINTTK);
            }
            pos++;
        }
        return false;
    }

    public boolean isAssignStmt() {
        if (!peek().is(Symbol.IDENFR)) {
            return false;
        }
        int pos = 1;
        while (!peek(pos).is(Symbol.SEMICN) && pos < tokens.size()) {
            if (peek(pos).is(Symbol.ASSIGN)) {
                return !peek(pos + 1).is(Symbol.GETINTTK);
            }
            pos++;
        }
        return false;
    }
}
