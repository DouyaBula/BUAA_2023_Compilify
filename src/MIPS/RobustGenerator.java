package MIPS;

import IR.Operand;
import IR.OperandType;
import IR.SymbolTable;
import IR.SymbolType;
import IR.TableTree;
import IR.Template;
import IR.Tuple;
import IR.TupleList;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;

public class RobustGenerator {
    private final ArrayList<String> mipsCode;
    private final CodePool codePool;
    private final BufferedWriter output;
    private int regLoop;
    private ActivationRecord currentAR;

    public RobustGenerator(BufferedWriter output) {
        mipsCode = new ArrayList<>();
        codePool = CodePool.getInstance();
        this.output = output;
        regLoop = 1;
        currentAR = null;
    }

    public void generate() {
        generateDataPart();
        generateTextPart();
    }

    // 回溯AR，找到变量的定义位置.
    // 返回回溯的层数，-1表示未定义
    private int getDefLevel(String name) {
        int back = 0;
        ActivationRecord ar = currentAR;
        while (ar != null) {
            if (ar.getDef(name) != null) {
                return back;
            }
            back++;
            ar = ar.getLastAR();
        }
        return -1;
    }

    // 回溯层数大于0时，返回变量的地址寄存器
    private String getDefAddrReg(int defLevel, String name) {
        ActivationRecord defAR = currentAR;
        String defAddrReg = allocateReg();
        mipsCode.add(codePool.code("move", defAddrReg, "$fp"));
        while (defLevel > 0) {
            defAR = defAR.getLastAR();
            mipsCode.add(codePool.code("lw", defAddrReg, "0(" + defAddrReg + ")"));
            defLevel--;
        }
        mipsCode.add(codePool.code("subiu", defAddrReg, defAddrReg,
                "" + defAR.getOffset(name)));
        return defAddrReg;
    }

    private String allocateReg() {
        String reg = "$t" + regLoop;
        regLoop = regLoop + 1 > 4 ? 1 : regLoop + 1;
        return reg;
    }

    private String allocateReg(Operand operand, boolean loadVal) {
        String reg = "$t" + regLoop;
        regLoop = regLoop + 1 > 4 ? 1 : regLoop + 1;
        String name = operand.getName();
        OperandType type = operand.getType();
        switch (type) {
            case TEMP:
                currentAR.addTemp(name);
                if (loadVal) {
                    mipsCode.add(codePool.code("lw", reg, -currentAR.getOffset(name) + "($sp)"));
                }
                break;
            case DEF:
                Template def = currentAR.getDefRecursively(name);
                if (def.isGlobal()) {
                    if (loadVal) {
                        mipsCode.add(codePool.code("lw", reg, name));
                    }
                } else {
                    if (loadVal) {
                        int defLevel = getDefLevel(name);
                        if (defLevel == 0) {
                            mipsCode.add(codePool.code(
                                    "lw", reg, -currentAR.getOffset(name) + "($fp)"));
                        } else if (defLevel > 0) {
                            String defAddrReg = getDefAddrReg(defLevel, name);
                            mipsCode.add(codePool.code("lw", reg, "(" + defAddrReg + ")"));
                        }
                    }
                }
                break;
            case CONSTVAL:
                if (loadVal) {
                    mipsCode.add(codePool.code("li", reg, "" + operand.getConstVal()));
                }
                break;
            case LABEL:
                if (loadVal) {
                    mipsCode.add(codePool.code("la", reg, name));
                }
                break;
            case STR:
                if (loadVal) {
                    mipsCode.add(codePool.code("la", reg, name.substring(1)));
                }
                break;
        }
        return reg;
    }

    private void saveReg(Operand target, String source) {
        OperandType type = target.getType();
        String name = target.getName();
        switch (type) {
            case TEMP:
                mipsCode.add(codePool.code("sw", source, -currentAR.getOffset(name) + "($sp)"));
                break;
            case DEF:
                Template def = currentAR.getDefRecursively(name);
                if (def.isGlobal()) {
                    mipsCode.add(codePool.code("sw", source, name));
                } else {
                    int defLevel = getDefLevel(name);
                    if (defLevel == 0) {
                        mipsCode.add(codePool.code("sw", source, -currentAR.getOffset(name) +
                                "($fp)"));
                    } else if (defLevel > 0) {
                        String defAddrReg = getDefAddrReg(defLevel, name);
                        mipsCode.add(codePool.code("sw", source, "(" + defAddrReg + ")"));
                    }
                }
                break;
            default:
                System.out.println(">> ERROR: not a savable reg");
                break;
        }
    }

    private void convertDEF(Tuple tuple) {
        Operand varOP = tuple.getOperand1();
        Template var = currentAR.getDef(varOP.getName());
        if (var == null) {
            System.out.println(">> ERROR: impossible def");
        }
        if ((var.is(SymbolType.VAR) || var.is(SymbolType.CONST))
                && !var.isGlobal()) {
            ArrayList<Operand> initVal = var.getInitVal();
            for (Operand initOp : initVal) {
                mipsCode.add(codePool.code("move", "$s0", allocateReg(initOp, true)));
                saveReg(varOP, "$s0");
            }
        }
    }

    private void convertASSIGN(Tuple tuple) {
        Operand target = tuple.getResult();
        Operand source = tuple.getOperand1();
        saveReg(target, allocateReg(source, true));
    }

    private void convertNOT(Tuple tuple) {
        Operand target = tuple.getResult();
        Operand source = tuple.getOperand1();
        String temp = allocateReg(source, true);
        mipsCode.add(codePool.code("seq", temp, "$zero", temp));
        saveReg(target, temp);
    }

    private void convertNEG(Tuple tuple) {
        Operand target = tuple.getResult();
        Operand source = tuple.getOperand1();
        String temp = allocateReg(source, true);
        mipsCode.add(codePool.code("negu", temp, temp));
        saveReg(target, temp);
    }

    private void convertPOS(Tuple tuple) {
        System.out.println("WARNING: POS should be never used");
    }

    private void convertCal(Tuple tuple, String op) {
        Operand target = tuple.getResult();
        Operand source1 = tuple.getOperand1();
        Operand source2 = tuple.getOperand2();
        allocateReg(target, false);
        String temp1 = allocateReg(source1, true);
        String temp2 = allocateReg(source2, true);
        mipsCode.add(codePool.code(op, temp1, temp1, temp2));
        saveReg(target, temp1);
    }

    private void convertADD(Tuple tuple) {
        convertCal(tuple, "addu");
    }

    private void convertSUB(Tuple tuple) {
        convertCal(tuple, "subu");
    }

    private void convertMUL(Tuple tuple) {
        convertCal(tuple, "mul");
    }

    private void convertDIV(Tuple tuple) {
        convertCal(tuple, "div");
    }

    private void convertMOD(Tuple tuple) {
        convertCal(tuple, "rem");
    }

    private void convertAND(Tuple tuple) {
        convertCal(tuple, "and");
    }

    private void convertOR(Tuple tuple) {
        convertCal(tuple, "or");
    }

    private void convertEQ(Tuple tuple) {
        convertCal(tuple, "seq");
    }

    private void convertNEQ(Tuple tuple) {
        convertCal(tuple, "sne");
    }

    private void convertLT(Tuple tuple) {
        convertCal(tuple, "slt");
    }

    private void convertGT(Tuple tuple) {
        convertCal(tuple, "sgt");
    }

    private void convertLEQ(Tuple tuple) {
        convertCal(tuple, "sle");
    }

    private void convertGEQ(Tuple tuple) {
        convertCal(tuple, "sge");
    }

    // function call
    private void convertCALL(Tuple tuple) {
        String resultReg = null;
        if (tuple.getResult() != null) {
            // 为返回值分配空间
            mipsCode.add("# allocate space for return value");
            resultReg = allocateReg(tuple.getResult(), false);
        }
        // 保存现场
        mipsCode.add("# save regs");
        currentAR.pushSth(32);
        mipsCode.addAll(codePool.saveRegs(currentAR.getStackSize()));
        // 将栈中参数复制到新的AR中
        mipsCode.add("# copy params");
        String funcName = tuple.getOperand1().getName();
        Template func = TableTree.getInstance().getTable(0).getContent().get(funcName);
        ArrayList<Operand> paramList = func.getParamList();
        for (int i = 0; i < paramList.size(); i++) {
            int oldOffset = currentAR.getStackSize() - 32 - 4 * (paramList.size() - i);
            int newOffset = currentAR.getStackSize() + 4 * i;
            mipsCode.add(codePool.code("lw", "$s0", -oldOffset + "($sp)"));
            mipsCode.add(codePool.code("sw", "$s0", -newOffset + "($sp)"));
        }
        // 调用函数
        mipsCode.add("# call function");
        mipsCode.add(codePool.code("jal", funcName + "_BEGIN"));
        // 弹出AR
        mipsCode.add("# pop AR");
        convertPopAR(null);
        // 函数返回值
        if (tuple.getResult() != null) {
            mipsCode.add("# get return value");
            mipsCode.add(codePool.code("move", resultReg, "$v0"));
            saveReg(tuple.getResult(), resultReg);
        }
        // 恢复现场
        mipsCode.add("# restore regs");
        mipsCode.addAll(codePool.restoreRegs(currentAR.getStackSize()));
    }

    private void convertRETURN(Tuple tuple) {
        Operand returnVal = tuple.getOperand1();
        if (returnVal != null) {
            mipsCode.add(codePool.code("move", "$v0", allocateReg(returnVal, true)));
        }
        mipsCode.add(codePool.code("jr", "$ra"));
    }

    private void convertLABEL(Tuple tuple) {
        Operand label = tuple.getOperand1();
        mipsCode.add(label.getName() + ":");
    }

    private void convertGOTO(Tuple tuple) {
        Operand label = tuple.getOperand1();
        mipsCode.add(codePool.code("j", label.getName()));
    }

    // WARNING: this should be never used
    private void convertJUMPTRUE(Tuple tuple) {
        System.out.println("WARNING: JUMPTRUE should be never used");
    }

    private void convertJUMPFALSE(Tuple tuple) {
        Operand cond = tuple.getOperand1();
        Operand label = tuple.getOperand2();
        mipsCode.add(codePool.code("beq", allocateReg(cond, true), "$zero", label.getName()));
    }

    private void convertPUSH(Tuple tuple) {
        Operand paramOp = tuple.getOperand1();
        String param = allocateReg(paramOp, true);
        mipsCode.add(codePool.code("sw", param, -currentAR.getStackSize() + "($sp)"));
        currentAR.pushSth();
    }

    private String calculateAddrReg(Operand base, Operand offset, boolean isLoadAddr) {
        Template def = currentAR.getDefRecursively(base.getName());
        String addrReg;
        if (offset == null) {   // isLoadAddr always true
            addrReg = allocateReg();
            if (def.isGlobal()) {
                mipsCode.add(codePool.code("la", addrReg, base.getName()));
            } else if (def.is(SymbolType.PARAM)) {
                mipsCode.add(codePool.code("move", addrReg, allocateReg(base, true)));
            } else {
                int defLevel = getDefLevel(base.getName());
                if (defLevel == 0) {
                    mipsCode.add(codePool.code(
                            "subu", addrReg, "$sp", "" + currentAR.getOffset(base.getName())));
                } else {
                    mipsCode.add(codePool.code(
                            "move", addrReg, getDefAddrReg(defLevel, base.getName())));
                }
            }
        } else {
            addrReg = allocateReg(offset, true);
            mipsCode.add(codePool.code("sll", addrReg, addrReg, "2"));
            if (def.getDimCnt() == 2 && isLoadAddr) {
                mipsCode.add(codePool.code("mul",
                        addrReg, addrReg, allocateReg(def.getDim2(), true)));
            }
            if (def.isGlobal()) {
                mipsCode.add(codePool.code("la", addrReg, base.getName() + "(" + addrReg + ")"));
            } else if (def.is(SymbolType.PARAM)) {
                mipsCode.add(codePool.code("subu", addrReg, addrReg, allocateReg(base, true)));
            } else {
                int defLevel = getDefLevel(base.getName());
                if (defLevel == 0) {
                    mipsCode.add(codePool.code("subu", addrReg, "$sp", addrReg));
                    mipsCode.add(codePool.code("subu", addrReg, addrReg,
                            "" + currentAR.getOffset(base.getName())));
                } else {
                    String defAddrReg = getDefAddrReg(defLevel, base.getName());
                    mipsCode.add(codePool.code("subu", addrReg, addrReg, defAddrReg));
                }
            }
        }
        return addrReg;
    }

    private void convertLOAD(Tuple tuple) {
        Operand base = tuple.getOperand1();
        Operand offset = tuple.getOperand2();
        Operand target = tuple.getResult();
        String offsetReg = calculateAddrReg(base, offset, false);
        String targetReg = allocateReg(target, false);
        mipsCode.add(codePool.code("lw", targetReg, "(" + offsetReg + ")"));
        saveReg(target, targetReg);
    }

    private void convertLOADADDR(Tuple tuple) {
        Operand base = tuple.getOperand1();
        Operand offset = tuple.getOperand2();
        Operand target = tuple.getResult();
        String addrReg = calculateAddrReg(base, offset, true);
        String targetReg = allocateReg(target, false);
        mipsCode.add(codePool.code("move", targetReg, addrReg));
        saveReg(target, targetReg);
    }

    private void convertSTORE(Tuple tuple) {
        Operand base = tuple.getOperand1();
        Operand offset = tuple.getOperand2();
        Operand source = tuple.getResult();
        String offsetReg = calculateAddrReg(base, offset, false);
        mipsCode.add(codePool.code("sw", allocateReg(source, true), "(" + offsetReg + ")"));
    }

    private void convertREAD(Tuple tuple) {
        mipsCode.addAll(codePool.syscall(5));
        String targetReg = allocateReg(tuple.getOperand1(), false);
        saveReg(tuple.getOperand1(), "$v0");
    }

    private void convertPRINT(Tuple tuple) {
        Operand source = tuple.getOperand1();
        String sourceReg = allocateReg(source, true);
        mipsCode.add(codePool.code("move", "$a0", sourceReg));
        switch (source.getType()) {
            case CONSTVAL, TEMP, DEF:
                mipsCode.addAll(codePool.syscall(1));
                break;
            case STR:
                mipsCode.addAll(codePool.syscall(4));
                break;
            default:
                System.out.println(">> ERROR: not a printable operand");
                break;
        }
    }

    private void convertEXIT(Tuple tuple) {
        mipsCode.addAll(codePool.syscall(10));
    }

    private void convertPushAR(Tuple tuple) {
        SymbolTable table = tuple.getBelongTable();
        allocateAR(table);
    }

    private void convertPopAR(Tuple tuple) {
        mipsCode.add(codePool.code("lw", "$fp", "0($fp)"));
        if (tuple != null) {
            currentAR = currentAR.getLastAR();
        }
        mipsCode.add(codePool.code("subi", "$sp", "$fp", "" + currentAR.getDefSize()));
    }

    // 生成全局数据段
    private void generateDataPart() {
        mipsCode.add(".data");
        SymbolTable rootTable = TableTree.getInstance().getTable(0);
        for (Template template : rootTable.getContent().values()) {
            if (template.is(SymbolType.VAR) || template.is(SymbolType.CONST)) {
                String name = template.getName();
                StringBuilder code = new StringBuilder(name + ": .word ");
                ArrayList<Operand> initVal = template.getInitVal();
                if (initVal.isEmpty()) {
                    int size = template.getDim1().getConstVal() == 0 ? 1 :
                            (template.getDim2().getConstVal() == 0 ?
                                    template.getDim1().getConstVal() :
                                    template.getDim1().getConstVal() * template.getDim2().getConstVal());
                    code.append("0 : ").append(size);
                } else {
                    for (int i = 0; i < initVal.size(); i++) {
                        code.append(initVal.get(i).getConstVal());
                        if (i != initVal.size() - 1) {
                            code.append(", ");
                        }
                    }
                }
                mipsCode.add(code.toString());
            }
        }
        for (int i = 0; i < TableTree.getInstance().getStringPool().size(); i++) {
            String str = TableTree.getInstance().getStringPool().get(i);
            mipsCode.add("str" + i + ": .asciiz \"" + str + "\"");
        }
    }

    // 生成代码段
    private void generateTextPart() {
        mipsCode.add(".text");
        allocateAR(TableTree.getInstance().getTable(0));    // allocate global AR
        // 跳转到main函数
        mipsCode.add(codePool.code("j", "main_BEGIN"));
        for (Tuple tuple : TupleList.getInstance().getTuples()) {
            TableTree.getInstance().setCurrentTable(tuple.getBelongTable());
            mipsCode.add("# " + tuple);
            convert(tuple);
            mipsCode.add("");
        }
    }

    // 分配AR
    private void allocateAR(SymbolTable table) {
        ActivationRecord newAR = new ActivationRecord(table, currentAR);
        if (currentAR != null) {
            mipsCode.add(codePool.code("subi", "$sp", "$fp", "" + currentAR.getSize()));
        }
        mipsCode.add(codePool.code("sw", "$fp", "0($sp)")); // save last AR addr
        mipsCode.add(codePool.code("move", "$fp", "$sp"));  // set fp
        mipsCode.add(codePool.code("subi", "$sp", "$fp", "" + newAR.getDefSize())); // set sp
        currentAR = newAR;
    }

    private void convert(Tuple tuple) {
        switch (tuple.getOperator()) {
            case DEF:
                convertDEF(tuple);
                break;
            case ASSIGN:
                convertASSIGN(tuple);
                break;
            case NOT:
                convertNOT(tuple);
                break;
            case NEG:
                convertNEG(tuple);
                break;
            case POS:
                convertPOS(tuple);
                break;
            case ADD:
                convertADD(tuple);
                break;
            case SUB:
                convertSUB(tuple);
                break;
            case MUL:
                convertMUL(tuple);
                break;
            case DIV:
                convertDIV(tuple);
                break;
            case MOD:
                convertMOD(tuple);
                break;
            case AND:
                convertAND(tuple);
                break;
            case OR:
                convertOR(tuple);
                break;
            case EQ:
                convertEQ(tuple);
                break;
            case NEQ:
                convertNEQ(tuple);
                break;
            case LT:
                convertLT(tuple);
                break;
            case GT:
                convertGT(tuple);
                break;
            case LEQ:
                convertLEQ(tuple);
                break;
            case GEQ:
                convertGEQ(tuple);
                break;
            case CALL:
                convertCALL(tuple);
                break;
            case RETURN:
                convertRETURN(tuple);
                break;
            case LABEL:
                convertLABEL(tuple);
                break;
            case GOTO:
                convertGOTO(tuple);
                break;
            case JUMPTRUE:
                convertJUMPTRUE(tuple);
                break;
            case JUMPFALSE:
                convertJUMPFALSE(tuple);
                break;
            case PUSH:
                convertPUSH(tuple);
                break;
            case LOAD:
                convertLOAD(tuple);
                break;
            case LOADADDR:
                convertLOADADDR(tuple);
                break;
            case STORE:
                convertSTORE(tuple);
                break;
            case READ:
                convertREAD(tuple);
                break;
            case PRINT:
                convertPRINT(tuple);
                break;
            case EXIT:
                convertEXIT(tuple);
                break;
            case PUSHAR:
                convertPushAR(tuple);
                break;
            case POPAR:
                convertPopAR(tuple);
                break;
            default:
                break;
        }
    }


    public void write() throws IOException {
        for (String code : mipsCode) {
            output.write(code + "\n");
        }
    }
}
