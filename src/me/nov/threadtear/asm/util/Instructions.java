package me.nov.threadtear.asm.util;

import java.util.HashMap;
import java.util.Map;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.Frame;

import me.nov.threadtear.asm.vm.VM;

public class Instructions implements Opcodes {
	public static InsnList copy(InsnList insnList) {
		InsnList copy = new InsnList();
		Map<LabelNode, LabelNode> labels = cloneLabels(insnList);
		for (AbstractInsnNode ain : insnList) {
			copy.add(ain.clone(labels));
		}
		return copy;
	}

	public static Map<LabelNode, LabelNode> cloneLabels(InsnList insns) {
		HashMap<LabelNode, LabelNode> labelMap = new HashMap<LabelNode, LabelNode>();
		for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getType() == AbstractInsnNode.LABEL) {
				labelMap.put((LabelNode) insn, new LabelNode());
			}
		}
		return labelMap;
	}

	public static boolean computable(AbstractInsnNode ain) {
		switch (ain.getType()) {
		case AbstractInsnNode.METHOD_INSN:
		case AbstractInsnNode.FIELD_INSN:
		case AbstractInsnNode.INVOKE_DYNAMIC_INSN:
		case AbstractInsnNode.VAR_INSN:
		case AbstractInsnNode.JUMP_INSN:
			return false;
		default:
			return !isCodeEnd(ain);
		}
	}

	public static boolean isCodeEnd(AbstractInsnNode ain) {
		switch (ain.getOpcode()) {
		case ATHROW:
		case RETURN:
		case ARETURN:
		case DRETURN:
		case FRETURN:
		case IRETURN:
		case LRETURN:
			return true;
		default:
			return false;
		}
	}

	public static boolean unnecessaryToStack(AbstractInsnNode ain) {
		switch (ain.getType()) {
		case AbstractInsnNode.LINE:
		case AbstractInsnNode.FIELD_INSN:
		case AbstractInsnNode.LABEL:
			return false;
		default:
			return true;
		}
	}

	public static boolean removeDeadCode(ClassNode cn, MethodNode mn) {
		Analyzer<?> analyzer = new Analyzer<>(new BasicInterpreter());
		try {
			analyzer.analyze(cn.name, mn);
		} catch (AnalyzerException e) {
			return false;
		}
		Frame<?>[] frames = analyzer.getFrames();
		AbstractInsnNode[] insns = mn.instructions.toArray();
		for (int i = 0; i < frames.length; i++) {
			AbstractInsnNode insn = insns[i];
			if (frames[i] == null && insn.getType() != AbstractInsnNode.LABEL) {
				mn.instructions.remove(insn);
				insns[i] = null;
			}
		}
		return true;
	}

	public static AbstractInsnNode getRealNext(AbstractInsnNode ain) {
		do {
			// skip labels, frames and line numbers
			ain = ain.getNext();
		} while (ain.getOpcode() == -1);
		return ain;
	}

	public static AbstractInsnNode getRealPrevious(AbstractInsnNode ain) {
		do {
			// skip labels, frames and line numbers
			ain = ain.getPrevious();
		} while (ain.getOpcode() == -1);
		return ain;
	}

	public static boolean isInteger(AbstractInsnNode ain) {
		int op = ain.getOpcode();

		switch (op) {
		case BIPUSH:
		case SIPUSH:
		case ICONST_M1:
		case ICONST_0:
		case ICONST_1:
		case ICONST_2:
		case ICONST_3:
		case ICONST_4:
		case ICONST_5:
			return true;
		}
		if (ain.getType() == AbstractInsnNode.LDC_INSN) {
			return ((LdcInsnNode) ain).cst instanceof Integer;
		}
		return false;
	}

	public static int getIntValue(AbstractInsnNode node) {
		if (node.getOpcode() >= ICONST_M1 && node.getOpcode() <= ICONST_5) {
			return node.getOpcode() - 3; // simple but effective
		}
		if (node.getOpcode() == SIPUSH || node.getOpcode() == BIPUSH) {
			return ((IntInsnNode) node).operand;
		}
		if (node.getType() == AbstractInsnNode.LDC_INSN) {
			return (Integer) ((LdcInsnNode) node).cst;
		}
		throw new IllegalArgumentException("not an int: " + node.getClass().getName());
	}

	public static InsnList isolateNonClassCalls(ClassNode cn, MethodNode mn) {
		InsnList copy = copy(mn.instructions);
		if (true) {
			return copy;
		}
		// TODO fix this
		for (int i = 0; i < copy.size(); i++) {
			AbstractInsnNode ain = copy.get(i);
			if (ain.getType() == AbstractInsnNode.METHOD_INSN) {
				MethodInsnNode min = (MethodInsnNode) ain;
				if (!min.owner.equals(cn.name) && !min.owner.matches(VM.RT)) {
					String inner = min.desc.substring(1, min.desc.lastIndexOf(')'));
					String outer = min.desc.substring(min.desc.lastIndexOf(')'));
					for (int size : Descriptor.calculateAmountArguments(inner)) {
						copy.insertBefore(min, new InsnNode(size > 1 ? POP2 : POP));
					}
					if (min.getOpcode() != INVOKESTATIC) {
						copy.insertBefore(min, new InsnNode(POP));
					}
					copy.set(min, createNullPushForType(outer));
				}
			} else if (ain.getType() == AbstractInsnNode.FIELD_INSN) {
				FieldInsnNode fin = (FieldInsnNode) ain;
				if (!fin.owner.equals(cn.name) && !fin.owner.matches(VM.RT)) {
					switch (fin.getOpcode()) {
					case GETFIELD:
						copy.insertBefore(fin, new InsnNode(POP));
						i += 1;
					case GETSTATIC:
						copy.set(fin, createNullPushForType(fin.desc));
						break;
					case PUTFIELD:
						copy.insertBefore(fin, new InsnNode(POP));
						copy.insertBefore(fin, new InsnNode(Descriptor.getSize(fin.desc.charAt(0)) > 1 ? POP2 : POP));
						copy.set(fin, createNullPushForType(fin.desc));
						i += 2;
						break;
					case PUTSTATIC:
						copy.insertBefore(fin, new InsnNode(Descriptor.getSize(fin.desc.charAt(0)) > 1 ? POP2 : POP));
						copy.set(fin, createNullPushForType(fin.desc));
						i += 1;
						break;
					}
				}
			}
			// TODO invokedynamic
		}
		return copy;
	}

	private static AbstractInsnNode createNullPushForType(String desc) {
		if (desc.startsWith("L") && desc.endsWith(";") || desc.startsWith("["))
			return new InsnNode(ACONST_NULL);

		char c = desc.charAt(0);
		if (c == 'D') {
			return new InsnNode(DCONST_0);
		} else if (c == 'F') {
			return new InsnNode(FCONST_0);
		} else if (c == 'J') {
			return new InsnNode(LCONST_0);
		}
		return new InsnNode(ICONST_0);
	}
}
