package com.comphenix.protocol.wrappers.nbt;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentMap;

import com.comphenix.protocol.injector.BukkitUnwrapper;
import com.comphenix.protocol.reflect.FuzzyReflection;
import com.comphenix.protocol.reflect.accessors.Accessors;
import com.comphenix.protocol.reflect.accessors.FieldAccessor;
import com.comphenix.protocol.reflect.accessors.MethodAccessor;
import com.comphenix.protocol.reflect.fuzzy.FuzzyMethodContract;
import com.comphenix.protocol.utility.EnhancerFactory;
import com.comphenix.protocol.utility.MinecraftMethods;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.comphenix.protocol.utility.MinecraftVersion;
import com.google.common.collect.Maps;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.InvocationHandlerAdapter;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.matcher.ElementMatchers;

import org.bukkit.block.BlockState;

/**
 * Manipulate tile entities.
 * @author Kristian
 */
class TileEntityAccessor<T extends BlockState> {
	private static final boolean BLOCK_DATA_INCL = MinecraftVersion.NETHER_UPDATE.atOrAbove();

	/**
	 * Token indicating that the given block state doesn't contain any tile entities.
	 */
	private static final TileEntityAccessor<BlockState> EMPTY_ACCESSOR = new TileEntityAccessor<BlockState>();

	/**
	 * Cached field accessors - {@link #EMPTY_ACCESSOR} represents no valid tile entity.
	 */
	private static final ConcurrentMap<Class<?>, TileEntityAccessor<?>> cachedAccessors = Maps.newConcurrentMap();

	private FieldAccessor tileEntityField;
	private MethodAccessor readCompound;
	private MethodAccessor writeCompound;

	// For CGLib detection
	private boolean writeDetected;
	private boolean readDetected;

	TileEntityAccessor() {
		// Do nothing
	}

	/**
	 * Construct a new tile entity accessor.
	 * @param tileEntityField - the tile entity field.
	 * @param state - the block state.
	 */
	private TileEntityAccessor(FieldAccessor tileEntityField, T state) {
		if (tileEntityField != null) {
			this.tileEntityField = tileEntityField;
			Class<?> type = tileEntityField.getField().getType();
			findMethods(type, state);
		}
	}

	void findMethods(Class<?> type, T state) {
		if (BLOCK_DATA_INCL) {
			Class<?> tileEntityClass = MinecraftReflection.getTileEntityClass();
			Class<?> iBlockData = MinecraftReflection.getIBlockDataClass();
			Class<?> nbtCompound = MinecraftReflection.getNBTCompoundClass();

			FuzzyReflection fuzzy = FuzzyReflection.fromClass(tileEntityClass, false);
			writeCompound = Accessors.getMethodAccessor(fuzzy.getMethod(
					FuzzyMethodContract.newBuilder()
							.banModifier(Modifier.STATIC)
							.returnTypeVoid()
							.parameterExactArray(iBlockData, nbtCompound)
							.build()));

			// this'll point to 2 methods, one of which points to the other
			readCompound = Accessors.getMethodAccessor(fuzzy.getMethod(
					FuzzyMethodContract.newBuilder()
							.banModifier(Modifier.STATIC)
							.returnTypeExact(nbtCompound)
							.parameterExactArray(nbtCompound)
							.build()));
		}

		// Possible read/write methods
		try {
			findMethodsUsingASM();
		} catch (IOException ex1) {
			try {
				// Much slower though
				findMethodUsingByteBuddy(state);
			} catch (Exception ex2) {
				throw new RuntimeException("Cannot find read/write methods in " + type, ex2);
			}
		}

		// Ensure we found them
		if (readCompound == null)
			throw new RuntimeException("Unable to find read method in " + type);
		if (writeCompound == null)
			throw new RuntimeException("Unable to find write method in " + type);
	}

	/**
	 * Find the read/write methods in TileEntity.
	 * @throws IOException If we cannot find these methods.
	 */
	private void findMethodsUsingASM() throws IOException {
		final Class<?> nbtCompoundClass = MinecraftReflection.getNBTCompoundClass();
		final Class<?> tileEntityClass = MinecraftReflection.getTileEntityClass();
		final ClassReader reader = new ClassReader(tileEntityClass.getCanonicalName());

		final String tagCompoundName = getJarName(MinecraftReflection.getNBTCompoundClass());
		final String expectedDesc = "(L" + tagCompoundName + ";)";

		reader.accept(new ClassVisitor(Opcodes.ASM5) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
				final String methodName = name;

				// Detect read/write calls to NBTTagCompound
				if (desc.startsWith(expectedDesc)) {
					return new MethodVisitor(Opcodes.ASM5) {
						private int readMethods;
						private int writeMethods;

						@Override
						public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean intf) {
							// This must be a virtual call on NBTTagCompound that accepts a String
							if (opcode == Opcodes.INVOKEVIRTUAL
									&& tagCompoundName.equals(owner)
									&& desc.startsWith("(Ljava/lang/String")) {

								// Is this a write call?
								if (desc.endsWith(")V")) {
									writeMethods++;
								} else {
									readMethods++;
								}
							}
						}

						@Override
						public void visitEnd() {
							if (readMethods > writeMethods) {
								readCompound = Accessors.getMethodAccessor(tileEntityClass, methodName, nbtCompoundClass);
							} else if (writeMethods > readMethods) {
								writeCompound = Accessors.getMethodAccessor(tileEntityClass, methodName, nbtCompoundClass);
							}

							super.visitEnd();
						}
					};
				}

				return null;
			}
		}, 0);
	}

	/**
	 * Find the read/write methods in TileEntity.
	 * @param blockState - the block state.
	 * @throws IOException If we cannot find these methods.
	 */
	private void findMethodUsingByteBuddy(T blockState) throws IOException, NoSuchMethodException, IllegalAccessException,
			InvocationTargetException, InstantiationException {
		final Class<?> nbtCompoundClass = MinecraftReflection.getNBTCompoundClass();

		Object compound = new ByteBuddy()
				.subclass(nbtCompoundClass)
				.name(MinecraftMethods.class.getPackage().getName() + ".NBTInvocationHandler")
				.method(ElementMatchers.not(ElementMatchers.isDeclaredBy(Object.class)))
				.intercept(InvocationHandlerAdapter.of((obj, method, args) -> {
					if (method.getReturnType().equals(Void.TYPE)) {
						// Write method
						writeDetected = true;
					} else {
						// Read method
						readDetected = true;
					}
					throw new RuntimeException("Stop execution.");
				}))
				.make()
				// TODO: Once the EnhancerFactory is removed, we'll need to get the ClassLoader from somewhere else.
				.load(EnhancerFactory.getInstance().getClassLoader(), ClassLoadingStrategy.Default.INJECTION)
				.getLoaded()
				.getDeclaredConstructor()
				.newInstance();
		Object tileEntity = tileEntityField.get(blockState);

		// Look in every read/write like method
		for (Method method : FuzzyReflection.fromObject(tileEntity, true).
				getMethodListByParameters(Void.TYPE, new Class<?>[] { nbtCompoundClass })) {

			try {
				readDetected = false;
				writeDetected = false;
				method.invoke(tileEntity, compound);
			} catch (Exception e) {
				// Okay - see if we detected a write or read
				if (readDetected)
					readCompound = Accessors.getMethodAccessor(method, true);
				if (writeDetected)
					writeCompound = Accessors.getMethodAccessor(method, true);
			}
		}
	}

	/**
	 * Retrieve the JAR name (slash instead of dots) of the given class.
	 * @param clazz - the class.
	 * @return The JAR name.
	 */
	private static String getJarName(Class<?> clazz) {
		return clazz.getCanonicalName().replace('.', '/');
	}

	/**
	 * Read the NBT compound that represents a given tile entity.
	 * @param state - tile entity represented by a block state.
	 * @return The compound.
	 */
	public NbtCompound readBlockState(T state) {
		NbtCompound output = NbtFactory.ofCompound("");
		Object tileEntity = tileEntityField.get(state);

		// Write the block state to the output compound
		writeCompound.invoke(tileEntity, NbtFactory.fromBase(output).getHandle());
		return output;
	}

	/**
	 * Write the NBT compound as a tile entity.
	 * @param state - target block state.
	 * @param compound - the compound.
	 */
	public void writeBlockState(T state, NbtCompound compound) {
		Object tileEntity = tileEntityField.get(state);

		// Ensure the block state is set to the compound
		if (BLOCK_DATA_INCL) {
			Object blockData = BukkitUnwrapper.getInstance().unwrapItem(state);
			readCompound.invoke(tileEntity, blockData, NbtFactory.fromBase(compound).getHandle());
		} else {
			readCompound.invoke(tileEntity, NbtFactory.fromBase(compound).getHandle());
		}
	}

	/**
	 * Retrieve an accessor for the tile entity at a specific location.
	 * @param state - the block state.
	 * @return The accessor, or NULL if this block state doesn't contain any tile entities.
	 */
	@SuppressWarnings("unchecked")
	public static <T extends BlockState> TileEntityAccessor<T> getAccessor(T state) {
		Class<?> craftBlockState = state.getClass();
		TileEntityAccessor<?> accessor = cachedAccessors.get(craftBlockState);

		// Attempt to construct the accessor
		if (accessor == null ) {
			TileEntityAccessor<?> created = null;
			FieldAccessor field = null;

			try {
				field = Accessors.getFieldAccessor(craftBlockState, MinecraftReflection.getTileEntityClass(), true);
			} catch (Exception e) {
				created = EMPTY_ACCESSOR;
			}
			if (field != null) {
				created = new TileEntityAccessor<T>(field, state);
			}
			accessor = cachedAccessors.putIfAbsent(craftBlockState, created);

			// We won the race
			if (accessor == null) {
				accessor = created;
			}
		}
		return (TileEntityAccessor<T>) (accessor != EMPTY_ACCESSOR ? accessor : null);
	}
}
