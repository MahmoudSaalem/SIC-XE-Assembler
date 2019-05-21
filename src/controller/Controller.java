package controller;

import java.nio.file.Paths;
import java.util.ArrayList;

import model.CommandInfo;
import model.Instruction;
import model.Line;
import model.Literal;
import model.ProgramCounter;
import model.SourceReader;
import model.Symbol;
import model.enums.Format;
import model.tables.DirectiveTable;
import model.tables.ErrorTable;
import model.tables.InstructionTable;
import model.tables.LiteralTable;
import model.tables.RegisterTable;
import model.tables.SymbolTable;
import model.utility.Utility;

public class Controller {

	private CommandInfo CI;

	private static ArrayList<Line> lineList;
	private static ArrayList<Integer> recordLengths = new ArrayList<>();
	private static ArrayList<Integer> reserves = new ArrayList<>();
	private static ArrayList<String> literals = new ArrayList<>();

	private String path;
	private String base;
	private String displacement;
	private String BASE_ERROR = "Base Error";
	private boolean noErrorsInPassOne = false;
	private boolean noErrorsInPassTwo = false;
	private ArrayList<String> objCodeForInst = new ArrayList<>();

	public boolean isNoErrors() {
		return noErrorsInPassOne && noErrorsInPassTwo;
	}

	public static void clear() {
		lineList.clear();
		recordLengths.clear();
		reserves.clear();
		literals.clear();
	}

	public void setNoErrors(boolean noErrors) {
		this.noErrorsInPassOne = noErrors;
	}

	private void loadInstructionTable() {

		InstructionTable.loadInstructionTable();
	}

	private void loadDirectiveTable() {

		DirectiveTable.loadDirectiveTable();
	}

	private void loadErrorList() {

		ErrorTable.loadErrorList();
	}

	private void loadRegisterTable() {

		RegisterTable.loadRegisterTable();
	}

	public void prepareData() {

		loadInstructionTable();
		loadDirectiveTable();
		loadErrorList();
		loadRegisterTable();
	}

	private void prepareListFile() {

		final String lineSeparator = "-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-";
		final String startPassOne = "\n-_-_-_-_-_-_-_-_-_- S   T   A   R   T      O   F      P   A   S   S   1 -_-_-_-_-_-_-_-_-_-_-";
		final String TABLE_FORM = "LINES" + Utility.getSpaces(7) + "ADDRESS" + Utility.getSpaces(5) + "LABEL"
				+ Utility.getSpaces(7) + "MNEMONIC" + Utility.getSpaces(4) + "ADDR_MODE" + Utility.getSpaces(3)
				+ "OPERAND1" + Utility.getSpaces(4) + "OPERAND2" + Utility.getSpaces(4) + "COMMENTS\n";
		String toBePrintedInListFile = lineSeparator;
		toBePrintedInListFile += startPassOne + "\n\n";
		toBePrintedInListFile += TABLE_FORM;

		int len = CI.getLinesList().size();
		for (int i = 0; i < len; i++) {
			String lineCount = String.valueOf(i);
			// noinspection StringConcatenationInLoop
			toBePrintedInListFile += lineCount + Utility.getSpaces(12 - lineCount.length())
					+ CI.getLinesList().get(i).toString() + "\n";
		}

		Utility.writeFile(toBePrintedInListFile, "res/LIST/listFile.txt");
	}

	private void fillSymbolTable() {
		Symbol symbol;
		String value;
		for (Line line : lineList) {
			if (!line.getLabel().equals("") && !line.getLabel().equals("(~)")) {
				if (line.getMnemonic().equalsIgnoreCase("EQU")) {
					if (Utility.isLabel(line.getFirstOperand())) {
						// if operand is label => get its address
						value = SymbolTable.symbolTable.get(line.getFirstOperand()).getAddress();
					} else {
						// if operand is expression => evaluate it
						if (Utility.isExpression(line.getFirstOperand())) {
							evaluateLineExpressions(line);
						}
						// reaching this line means operand is not a label
						// if it is an expression then it is evaluated a replaced by the final result
						// if not => it is a numeric value already
						// in both cases, the needed value is the operand itself
						value = line.getFirstOperand();
					}
					symbol = new Symbol(line.getLabel(), value);
					SymbolTable.symbolTable.put(symbol.getSymbol(), symbol);
				} else {
					symbol = new Symbol(line.getLabel(), line.getLocation());
					SymbolTable.symbolTable.put(symbol.getSymbol(), symbol);
				}
			}
		}
		Utility.writeFile(SymbolTable.getString(), "res/LIST/symTable.txt");
	}

	public static void fillLiteralsTable(ArrayList<Line> lineList) {
		Literal literal;
		// In case LTORG was encountered in the code, all literals before it are
		// evaluated and added.
		// Then, "literalsStartIndex" is set to the index of the first line after LTORG
		// So that, when this function s called at the end of the program, it doesn't
		// add already added literals
		int index = ProgramCounter.getInstance().getLiteralsStartIndex();
		int startingAddress = ProgramCounter.getInstance().getProgramCounter();
		int i = 0;
		for (Line line : lineList) {
			if (line.getError().equals("")) {
				// to skip iterations before the last encountered LTORG in the program
				if (i <= index) {
					i++;
					continue;
				}
				// to add literals after the last LTORG to the pole (i.e. after END directive)
				if (!line.getFirstOperand().equals("")) {
					if (line.getFirstOperand().charAt(0) == '=') {
						literal = new Literal(line.getFirstOperand(), Utility.convertToHexa(startingAddress));
						startingAddress += literal.calculateLength();
						LiteralTable.literalTable.put(literal.getOperand(), literal);
					}
				}
				index++;
			}
		}
		ProgramCounter.getInstance().setLiteralsStartIndex(index);
		ProgramCounter.getInstance().setLocationCounter(startingAddress);
	}

	private void processArithmeticExpressions() {
		for (Line line : lineList) {
			if (line.getError().equalsIgnoreCase("")) {
				if (!line.getMnemonic().equals("NOP")) {
					Format format;
					if (Utility.isInstruction(line.getMnemonic())) {
						format = InstructionTable.instructionTable.get(line.getMnemonic()).getFormat();
					} else { // Directive
						format = DirectiveTable.directiveTable.get(line.getMnemonic()).getFormat();
					}
					// Only if formats 3 & 4
					if (format == Format.THREE || format == Format.FOUR || line.getMnemonic().equals("ORG")
							|| line.getMnemonic().equals("EQU") || line.getMnemonic().equals("LTORG")) {
						// ONLY if addressing mode is direct with/without indexing
						if (!line.getAddressingMode().equals("#") && !line.getAddressingMode().equals("@")) {
							if (Utility.isExpression(line.getFirstOperand()))
								evaluateLineExpressions(line);
						}
					}
				}
			}
		}
	}

	private void evaluateLineExpressions(Line line) {
		ArrayList<String> expressionList = Utility.splitExpression(line.getFirstOperand());
		// Verify labels in the expression
		if (Utility.verifyExpression(expressionList)) {
			// Replace labels by the numeric value of their addresses
			Utility.evaluateLabels(expressionList);
			// Check syntax of arithmetic expression
			if (Utility.validateNumericExpression(expressionList)) {
				// Evaluate the expression
				String expression = Utility.getNumericExpression(expressionList);
				String operand = Utility.evaluateExpression(expression);
				if (operand.equals("error"))
					line.setError(String.valueOf(ErrorTable.WRONG_OPERAND_TYPE));
				System.out.println(
						"Done evaluating! " + line.getFirstOperand() + " = " + operand + "\t\t\t" + expression);
				line.setFirstOperand(operand);
			} else {
				// Wrong Arithmetic expression format
				line.setError(ErrorTable.errorList[ErrorTable.WRONG_OPERAND_TYPE]);
			}
		} else {
			line.setError(ErrorTable.errorList[ErrorTable.WRONG_OPERAND_TYPE]);
		}
	}

	private void passOne(String program, boolean restricted) {

		Utility.writeFile(program, "res/functionality/ASSEMBLING");
		CI = SourceReader.getInstance().processFile(SourceReader.getInstance().readFile("res/functionality/ASSEMBLING"),
				restricted);

		boolean firstPassDone = CI.addToLineList();
		lineList = CI.getLinesList();
		if (firstPassDone) {
			prepareListFile();
			fillSymbolTable();
			processArithmeticExpressions();
			fillLiteralsTable(lineList);
		}
		noErrorsInPassOne = CI.checkForErrors();
	}

	private String getStartOfProgram() {

		String startOfProgram = "000000";
		for (Line line : lineList) {
			if (line.getMnemonic().equalsIgnoreCase("START")) {
				startOfProgram = "00" + line.getLocation();
				break;
			}
		}
		return startOfProgram;
	}

	private String getSizeOfProgram() {
		int sum = 0;
		for (int n : recordLengths) {
			sum += n;
		}
		for (int n : reserves) {
			sum += n;
		}
		String res = Utility.convertToHexa(sum - 1);
		return "00" + res;
	}

	private String getProgramName() {

		String name = "";
		for (Line line : lineList) {
			if (line.getMnemonic().equalsIgnoreCase("START")) {
				name = line.getLabel();
				break;
			}
		}
		int length = name.length();
		if (length > 6)
			name = name.substring(0, 6);
		if (length < 6)
			name += Utility.getSpaces(6 - length);
		return name;
	}

	private String getHeaderRecord() {

		String startOfProgram = getStartOfProgram();
		String sizeOfProgram = getSizeOfProgram();
		String programName = getProgramName();
		return "H^" + programName + "^" + startOfProgram + "^" + sizeOfProgram; // Return header Record
	}

	private String addToTextRecord(String opcode, String flags, String disp, Format format) {

		String record;
		record = Utility.hexToBin(opcode).substring(12, 18); // take 6 bits only
		record += Utility.hexToBin(flags).substring(14);
		record += format == Format.THREE ? Utility.hexToBin(disp).substring(8) : Utility.hexToBin(disp);
		record = Utility.binToHex(record, format);
		return record;
	}

	private String getNIX(Line line) {

		String nix;
		switch (line.getAddressingMode()) {
		// set n, i and x flags
		case "#":
			// case immediate
			// nix = 010
			nix = "010";
			break;
		case "@":
			// case indirect
			// nix = 100
			nix = "100";
			break;
		default:
			// case direct
			// nix = indexing? 111 : 110;
			if (!line.getSecondOperand().equals("")) {
				// indexed
				nix = "111";
			} else {
				// non indexed
				nix = "110";
			}
			break;
		}
		return nix;
	}

	private String getBPE(Line line, Format format) {

		// TODO: set bpe
		// TODO: firstOperand = displacement and set the b, p and e flags, e = 0 for
		// Format 3 and e = 1 for Format 4
		String bpe, bp, e;
		String firstOperand = line.getFirstOperand().toUpperCase();
		int step = format == Format.THREE ? 3 : 4;
		int pc = Utility.hexToDecimal(line.getLocation()) + step;
		int loc, disp;
		Symbol symbol = SymbolTable.symbolTable.get(firstOperand);
		Literal literal = LiteralTable.literalTable.get(firstOperand);
		if (symbol != null || literal != null) {
			loc = literal == null ? Utility.hexToDecimal(symbol.getAddress())
					: Utility.hexToDecimal(literal.getAddress());
			disp = loc - pc;
			if (disp >= -2048 && disp < 2048) {
				// bpe = 010
				bp = "01";
			} else { // try base relative
				if (checkBase()) { // check if base register is available
					int base = getBase();
					disp = loc - base;
					if (disp >= 0 && disp <= 4 * 1024 - 1) {
						// bpe = 100
						bp = "10";
					} else {
						// error
						line.setError(ErrorTable.errorList[ErrorTable.DISPLACEMENT_OVERFLOW]);
						return BASE_ERROR;
					}
				} else {
					// error
					line.setError(ErrorTable.errorList[ErrorTable.DISPLACEMENT_OVERFLOW]);
					return BASE_ERROR;
				}
			}
		} else { // copied and pasted code
			bp = "00";
			// immediate
			if (firstOperand.charAt(0) == '#') {
				disp = Utility.hexToDecimal(firstOperand.substring(1));
			} else { // address
				disp = Utility.hexToDecimal(firstOperand);
			}
		}
		displacement = format == Format.THREE ? String.format("%1$04X", disp) : String.format("%1$05X", disp);
		e = format == Format.THREE ? "0" : "1";
		bpe = bp + e;
		return bpe;
	}

	private void setBase(String base) {

		this.base = SymbolTable.symbolTable.get(base).getAddress();
	}

	private int getBase() {

		return Utility.hexToDecimal(base);
	}

	private boolean checkBase() {

		for (Line line : lineList) {
			if (line.getMnemonic().equalsIgnoreCase("BASE")) {
				setBase(line.getFirstOperand());
				return true;
			} else if (line.getMnemonic().equalsIgnoreCase("NOBASE"))
				return false;
		}
		return false;
	}

	private String extractOperand(String operand) {

		return operand.substring(2).replace("'", "");
	}

	private String convertToAscii(String data) {

		String res = "";
		char[] charArray = data.toCharArray();
		for (char c : charArray) {
			// noinspection StringConcatenationInLoop
			res += (int) c;
		}
		return res;
	}

	private String extractLiteral(String literal) {
		return literal.substring(3, literal.length()-1);
	}

	private String getLiteralHexValue(Literal literal) {
		String temp = extractLiteral(literal.getOperand());
		switch (literal.getType()) {
		case "W":
			recordLengths.add(3);
			return Utility.getZeros(6 - convertToAscii(temp).length()) + convertToAscii(temp);
		case "C":
			recordLengths.add(temp.length());
			return convertToAscii(temp);
		case "X":
			recordLengths.add((int)Math.ceil(temp.length() / 2));
			return temp;
		default:
			return null; 
		}
	}

	private String ltorgOccured() {
		String record = "";
		while (!literals.isEmpty()) {
			record += getLiteralHexValue(LiteralTable.literalTable.get(literals.get(0)));
			literals.remove(0);
		}
		return record;
	}

	private String formatTextRecord(String textRecord) {
		ArrayList<String> lengths = new ArrayList<>();
		String start = getStartOfProgram();
		String result = "T^" + start + "^^";
		char[] temp = textRecord.toCharArray();
		int count = 0;
		int sum = 0;
		int index = 0;
		int tempSize = 0;
		for (int n : recordLengths) {
			sum += n;
			if (sum <= 30) {
				int i;
				for (i = index; i < index + n * 2; i++) {
					// noinspection StringConcatenationInLoop
					result += temp[i];
					count++;
				}
				objCodeForInst.add(result.substring(result.length() - 6));
				tempSize = sum;
				index = i;
			} else {
				// noinspection StringConcatenationInLoop
				result += "\nT^";
				start = String.format("%1$06X", Utility.hexToDecimal(start) + sum - n);
				lengths.add(String.format("%1$02X", sum - n));
				result += start;
				result += "^^";
				tempSize = sum - n;
				sum = 0;
			}
		}
		lengths.add(String.format("%1$02X", tempSize));
		int diff = temp.length - count;
		if (count < temp.length) {
			while (count < temp.length) {
				result += temp[count++];
			}
			String lastSize = Integer.toString(Utility.hexToDecimal(lengths.get(lengths.size() - 1)) + diff / 2);
			if (Integer.parseInt(lastSize) > 30) { lastSize = Integer.toString(Integer.parseInt(lastSize) - 28); }
			lastSize = String.format("%1$02X", Integer.parseInt(lastSize));
			lengths.set(lengths.size() - 1, lastSize);
		}
		int size = result.length();
		index = 0;
		for (int i = 0; i < size; i++) {
			if (result.charAt(i) == 'T') {
				result = result.substring(0, i + 9) + lengths.get(index++) + result.substring(i + 9);
			}
		}
		return result;
	}

	private String getTextRecord() {

		String textRecord = "";
		String nix, bpe;
		String flagsByte;
		String textRecordTemp;
		String firstOperand;
		String secondOperand;
		String mnemonic;
		Instruction currentInstruction;
		for (Line line : lineList) {
			mnemonic = line.getMnemonic();
			currentInstruction = InstructionTable.instructionTable.get(mnemonic);
			if (currentInstruction != null) {
				textRecordTemp = String.format("%1$02X", currentInstruction.getOpcode());
				firstOperand = line.getFirstOperand();
				if (LiteralTable.literalTable.get(firstOperand) != null) {
					literals.add(firstOperand);
				}
				switch (currentInstruction.getFormat()) {
				case ONE:
					// noinspection StringConcatenationInLoop
					textRecord += textRecordTemp;
					recordLengths.add(1);
					break;
				case TWO:
					firstOperand = Integer.toString(RegisterTable.registerTable.get(line.getFirstOperand()));
					if (currentInstruction.hasSecondOperand())
						secondOperand = Integer.toString(RegisterTable.registerTable.get(line.getSecondOperand()));
					else
						secondOperand = "0";
					// noinspection StringConcatenationInLoop
					textRecord += textRecordTemp + firstOperand + secondOperand;
					recordLengths.add(2);
					break;
				case THREE:
					nix = getNIX(line);
					bpe = getBPE(line, Format.THREE);
					if (bpe.equals(BASE_ERROR)) {
						return BASE_ERROR;
					}
					flagsByte = Utility.binToHex(nix + bpe);
					// noinspection StringConcatenationInLoop
					textRecord += addToTextRecord(textRecordTemp, flagsByte, displacement, Format.THREE);
					recordLengths.add(3);
					break;
				case FOUR:
					// noinspection UnusedAssignment
					firstOperand = line.getFirstOperand();
					nix = getNIX(line);
					bpe = getBPE(line, Format.FOUR);
					if (bpe.equals(BASE_ERROR)) {
						return BASE_ERROR;
					}
					flagsByte = Utility.binToHex(nix + bpe);
					// noinspection StringConcatenationInLoop
					textRecord += addToTextRecord(textRecordTemp, flagsByte, displacement, Format.FOUR);
					recordLengths.add(4);
					break;
				default:
					break;
				}
			} else {
				// Directive
				String data = line.getFirstOperand().toUpperCase();
				String[] operands = data.split(",");
				char type;
				switch (mnemonic) {
				case "WORD":
					for (String operand : operands) {
						type = operand.charAt(0);
						switch (type) {
						case 'X':
							// textRecord += Utility.getZeros(6 - operand.length()) +
							// extractOperand(operand);
							break;
						case 'C':
							// textRecord += convertToAscii(extractOperand(operand));
							break;
						default:
							// noinspection StringConcatenationInLoop
							textRecord += String.format("%1$06X", Integer.parseInt(operand));
							recordLengths.add(3);
							break;
						}
					}
					break;
				case "BYTE":
					for (String operand : operands) {
						type = operand.charAt(0);
						switch (type) {
						case 'X':
							operand = extractOperand(operand);
							textRecordTemp = Utility.getZeros(
									(int) Math.ceil((double) (operand.length()) / 2) * 2 - operand.length()) + operand;
							// noinspection StringConcatenationInLoop
							textRecord += textRecordTemp;
							recordLengths.add(textRecordTemp.length() / 2);
							break;
						case 'C':
							operand = extractOperand(operand);
							// noinspection StringConcatenationInLoop
							textRecord += convertToAscii(operand);
							recordLengths.add(operand.length());
							break;
						default:
							// noinspection StringConcatenationInLoop
							textRecord += String.format("%1$02X", Integer.parseInt(operand));
							recordLengths.add(1);
							break;
						}
					}
					break;
				case "RESW":
					firstOperand = line.getFirstOperand();
					reserves.add(3 * Integer.parseInt(firstOperand));
					break;
				case "RESB":
					firstOperand = line.getFirstOperand();
					reserves.add(Integer.parseInt(firstOperand));
					break;
				case "LTORG":
					textRecord += ltorgOccured();
					break;
				default:
					break;
				}
			}
		}
		return formatTextRecord(textRecord + ltorgOccured());
	}

	private String getAddressOfFirstExecutableInstruction() {

		String label, address;
		label = address = null;
		for (Line line : lineList) {
			if (line.getMnemonic().equalsIgnoreCase("END")) {
				label = line.getFirstOperand();
				break;
			}
		}
		for (Line line : lineList) {
			if (line.getLabel().equalsIgnoreCase(label)) {
				address = line.getLocation();
			}
		}
		return "00" + address;
	}

	private String getEndRecord() {

		String addressOfFirstExecutableInstruction = getAddressOfFirstExecutableInstruction();
		return "E^" + addressOfFirstExecutableInstruction; // Return endRecord
	}

	private String getObjectCode() {

		String textRecord = getTextRecord();
		if (textRecord.equals(BASE_ERROR))
			return BASE_ERROR;
		String headerRecord = getHeaderRecord();
		String endRecord = getEndRecord();
		return headerRecord + "\n" + textRecord + "\n" + endRecord; // Return objectCode
	}

	private void passTwo() {

		String objectCode = getObjectCode();
		reportEndPassTwo();
		if (objectCode.equals(BASE_ERROR)) {
			noErrorsInPassTwo = false;
			return;
		}
		Utility.writeFile(objectCode, "res/LIST/objFile.o");
		noErrorsInPassTwo = true;
	}

	public void assemble(String program, boolean restricted) {

		try {
			passOne(program, restricted);
			if (noErrorsInPassOne)
				passTwo();
		} catch (Exception e) {
			System.out.println("=================\nERROR IN ASSEMBLY\n=================");
			e.printStackTrace();
		}
		Utility.clearAll();
	}

	public String getListFile() {

		path = Paths.get(".").toAbsolutePath().normalize().toString() + "/res/LIST/listFile.txt";
		ArrayList<String> arr = SourceReader.getInstance().readFile(path);
		String append = "";
		for (String s : arr) {
			// noinspection StringConcatenationInLoop
			append += s + "\n";
		}
		return append;
	}

	public String loadFile(String path) {

		this.path = path;
		ArrayList<String> arr = SourceReader.getInstance().readFile(path);
		String append = "";
		for (String s : arr) {
			// noinspection StringConcatenationInLoop
			append += s + "\n";
		}
		return append;
	}

	private void reportEndPassTwo() {
		String append = getListFile();

		final String lineSeparator = "-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-";
		final String startPassTwo = "\n-_-_-_-_-_-_-_-_-_- S   T   A   R   T      O   F      P   A   S   S   2 -_-_-_-_-_-_-_-_-_-_-\n\n";

		final String TABLE_FORM = "LINES" + Utility.getSpaces(7) + "Code" + Utility.getSpaces(5) + " LC"
				+ Utility.getSpaces(7) + "Source Statement\n\n";
		append = append + lineSeparator + startPassTwo + TABLE_FORM;
		int len = CI.getLinesList().size();
		ArrayList<String> codeInstToBePrinted = new ArrayList<>();
		for (int i = 0; i < len; i++) {
			codeInstToBePrinted.add(Utility.getSpaces(6));
		}

		boolean displacementError = false;
		ArrayList<String> buffer = new ArrayList<>();
		for (int i = 0; i < len; i++) {
			buffer.add("");
			String lineCount = String.valueOf(i);
			String instructionTobeWritten = CI.getLinesList().get(i).toString();
			Instruction currentInstruction = InstructionTable.instructionTable.get(lineList.get(i).getMnemonic());
			if (Utility.isInstruction(lineList.get(i).getMnemonic()) && (currentInstruction.getFormat() == Format.THREE
					|| currentInstruction.getFormat() == Format.FOUR)) {
				String NIX = getNIX(lineList.get(i));
				String BPE = getBPE(lineList.get(i), currentInstruction.getFormat());
				if (BPE.equals(BASE_ERROR)) {
					displacementError = true;
				} else {
					buffer.set(i, nixBpeToString(NIX, BPE));
				}
			}
			buffer.set(i,
					buffer.get(i) + lineCount + Utility.getSpaces(12 - lineCount.length()) + codeInstToBePrinted.get(i)
							+ Utility.getSpaces(20 - (codeInstToBePrinted.get(i).length() + (12 - lineCount.length())))
							+ instructionTobeWritten + "\n");
		}
		if (!displacementError) {
			codeInstToBePrinted = codeForInstListFile();
		}
		for (int i = 0; i < len; i++) {
			String firstPart = "";
			String secondPart = "";

			if (codeInstToBePrinted.get(i).equals("")) {
				codeInstToBePrinted.set(i, Utility.getSpaces(6));
			}

			if (Character.isDigit(buffer.get(i).charAt(0))) {
				firstPart = buffer.get(i).substring(0, 14 - Integer.toString(i).length()) + codeInstToBePrinted.get(i);
				secondPart = buffer.get(i).substring(18);
			} else {
				firstPart = buffer.get(i).substring(0, 81 + (12 - Integer.toString(i).length()))
						+ codeInstToBePrinted.get(i);
				secondPart = buffer.get(i).substring(97);
			}
			append += firstPart + secondPart;
		}
		Utility.writeFile(append, "res/LIST/listFile.txt");
	}

	private ArrayList<String> codeForInstListFile() {
		ArrayList<String> codeToBePrinted = new ArrayList<>();
		for (int i = 0; i < lineList.size(); i++)
			codeToBePrinted.add("");

		int j = 0;
		for (int i = 0; i < lineList.size(); i++) {
			Instruction currentInstruction = InstructionTable.instructionTable.get(lineList.get(i).getMnemonic());
			if (Utility.isInstruction(lineList.get(i).getMnemonic())) {
				switch (currentInstruction.getFormat()) {
				case FOUR:
				case THREE:
					codeToBePrinted.set(i, objCodeForInst.get(j));
					j++;
					break;
				case TWO:
					codeToBePrinted.set(i, objCodeForInst.get(j).substring(2) + Utility.getSpaces(2));
					j++;
					break;
				case ONE:
					codeToBePrinted.set(i, objCodeForInst.get(j).substring(4) + Utility.getSpaces(4));
					j++;
					break;
				default:
					break;
				}
			} else if ((lineList.get(i).getMnemonic().equalsIgnoreCase("WORD")
					|| lineList.get(i).getMnemonic().equalsIgnoreCase("BYTE"))) {
				codeToBePrinted.set(i, objCodeForInst.get(j));
				j++;
			} else {
				codeToBePrinted.set(i, Utility.getSpaces(6));
			}
		}
		return codeToBePrinted;
	}

	private String nixBpeToString(String NIX, String BPE) {
		return "\n" + Utility.getSpaces(40) + "n=" + NIX.charAt(0) + Utility.getSpaces(4) + "i=" + NIX.charAt(1)
				+ Utility.getSpaces(4) + "x=" + NIX.charAt(2) + Utility.getSpaces(4) + "b=" + BPE.charAt(0)
				+ Utility.getSpaces(4) + "p=" + BPE.charAt(1) + Utility.getSpaces(4) + "e=" + BPE.charAt(2) + "\n";
	}
}
