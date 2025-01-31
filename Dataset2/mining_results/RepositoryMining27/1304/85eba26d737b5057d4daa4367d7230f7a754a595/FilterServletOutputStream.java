/*
 * Copyright 2008-2014 by Emeric Vernat
 *
 *     This file is part of Java Melody.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.bull.javamelody;

import java.io.IOException;
import java.io.OutputStream;

import javax.servlet.ServletOutputStream;

/**
 * Output stream pour un filtre implémentant ServletOutputStream à partir d'un OutputStream.
 * @author Emeric Vernat
 */
class FilterServletOutputStream extends ServletOutputStream {
	private final OutputStream stream;

	/**
	 * Constructeur.
	 * @param output OutputStream
	 */
	FilterServletOutputStream(OutputStream output) {
		super();
		assert output != null;
		stream = output;
	}

	/** {@inheritDoc} */
	@Override
	public void close() throws IOException {
		stream.close();
	}

	/** {@inheritDoc} */
	@Override
	public void flush() throws IOException {
		stream.flush();
	}

	/** {@inheritDoc} */
	@Override
	public void write(int b) throws IOException {
		stream.write(b);
	}

	/** {@inheritDoc} */
	@Override
	public void write(byte[] bytes) throws IOException {
		stream.write(bytes);
	}

	/** {@inheritDoc} */
	@Override
	public void write(byte[] bytes, int off, int len) throws IOException {
		stream.write(bytes, off, len);
	}
}
